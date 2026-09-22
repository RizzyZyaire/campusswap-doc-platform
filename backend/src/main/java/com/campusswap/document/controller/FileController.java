package com.campusswap.document.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.document.service.impl.FileStorageService;
import com.campusswap.document.vo.ImageVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口（API_SPECIFICATION §4.9.1）。
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * 上传图片，返回相对 URL。
     *
     * @param file 表单字段名固定为 {@code file}
     * @return 图片相对 URL
     */
    @PostMapping("/image")
    @RequiresPermission("doc:upload")
    public ResponseResult<ImageVo> uploadImage(@RequestParam("file") MultipartFile file) {
        return ResponseResult.ok(fileStorageService.store(file));
    }
}
