package com.campusswap.document.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.document.vo.ImageVo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传服务（API_SPECIFICATION §4.9.1，BR-15 / NFR-S6）。
 *
 * <p>三重白名单：扩展名 ∈ {jpg,jpeg,png,webp,gif} + MIME 与扩展名一致 + 单文件 ≤ 5MB；
 * 落盘路径 {@code backend/uploads/yyyy/MM/{uuid}.{ext}}，入库与返回都是<b>相对 URL</b>。
 * 原始文件名只回显、不参与路径拼接（防路径穿越）。</p>
 *
 * <p><b>不开事务</b>：磁盘 IO 不进事务边界（ARCHITECTURE §8）。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
public class FileStorageService {

    /** 扩展名 → 允许的 MIME（小写比较）。 */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "jpg", Set.of("image/jpeg", "image/jpg"),
            "jpeg", Set.of("image/jpeg", "image/jpg"),
            "png", Set.of("image/png"),
            "webp", Set.of("image/webp"),
            "gif", Set.of("image/gif"));

    /** 单文件大小上限 5MB（BR-15）。 */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 统一的格式错误提示。 */
    private static final String FORMAT_MESSAGE = "仅支持 jpg、jpeg、png、webp、gif 格式，且单张不超过5MB";

    /** 上传根目录（相对后端工程根）。 */
    private static final String UPLOAD_ROOT = "uploads";

    /**
     * 保存图片并返回相对 URL。
     *
     * @param file 上传文件
     * @return 图片相对 URL
     */
    public ImageVo store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, FORMAT_MESSAGE);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, FORMAT_MESSAGE);
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !ALLOWED.containsKey(extension)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, FORMAT_MESSAGE);
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED.get(extension).contains(contentType)) {
            log.warn("上传被拒：扩展名 {} 与 MIME {} 不符", extension, contentType);
            throw new BusinessException(ErrorCode.BAD_REQUEST, FORMAT_MESSAGE);
        }

        LocalDate today = LocalDate.now();
        String relativeDir = today.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        // 文件名 UUID 化：原始名一律不参与路径拼接
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path targetDir = Paths.get(UPLOAD_ROOT, relativeDir).toAbsolutePath().normalize();
        Path target = targetDir.resolve(fileName).normalize();
        if (!target.startsWith(targetDir)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, FORMAT_MESSAGE);
        }
        try {
            Files.createDirectories(targetDir);
            file.transferTo(target.toFile());
        } catch (IOException ex) {
            log.error("图片落盘失败", ex);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "图片保存失败，请稍后重试");
        }
        String url = "/" + UPLOAD_ROOT + "/" + relativeDir + "/" + fileName;
        log.info("图片已保存: {} ({} bytes)", url, file.getSize());
        return new ImageVo(url);
    }

    /**
     * 取小写扩展名。
     *
     * @param originalFilename 原始文件名
     * @return 扩展名；无扩展名时返回 null
     */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
