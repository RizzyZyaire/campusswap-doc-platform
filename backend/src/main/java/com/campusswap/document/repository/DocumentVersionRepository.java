package com.campusswap.document.repository;

import com.campusswap.entity.DocumentVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 文档版本仓储（doc_version）：追加型留痕，只增不改不删。
 *
 * @author Zyaire
 */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    /** 某文档的全部版本，按版本号倒序（最新在前）。 */
    List<DocumentVersion> findByDocumentIdOrderByVersionNumDesc(Long documentId);

    /** 某文档的指定版本（版本对比用，命中唯一键 {@code uk_doc_version}）。 */
    DocumentVersion findByDocumentIdAndVersionNum(Long documentId, Integer versionNum);

    /** 某文档的版本条数。 */
    long countByDocumentId(Long documentId);
}
