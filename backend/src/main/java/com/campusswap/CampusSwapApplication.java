package com.campusswap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * CampusSwap 文档管理平台后端启动类。
 *
 * <p>分层与包结构见 docs/02-design/ARCHITECTURE.md §3.2：
 * {@code common}（公共设施）/ {@code config}（配置）/ {@code entity}（实体与枚举，顶层集中）
 * / {@code system}（系统与权限模块）/ {@code document}（文档业务模块）。</p>
 *
 * @author Zyaire
 */
@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class CampusSwapApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CampusSwapApplication.class, args);
    }
}
