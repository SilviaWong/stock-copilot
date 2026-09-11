package com.stock.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;

/**
 * 自适应 SQLite 数据源配置
 * 解决在不同目录(根目录/backend目录/IDEA)启动时因相对路径找不到 data 目录的问题
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    @Primary
    public DataSource dataSource() {
        File dbFile = resolveDatabaseFile();
        try {
            // 确保父目录存在
            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean ok = parentDir.mkdirs();
                log.info("【Stock Copilot】自动创建 SQLite 数据目录: {}, 结果: {}", parentDir.getCanonicalPath(), ok);
            }
            log.info("【Stock Copilot】SQLite 数据库文件绝对路径: {}", dbFile.getCanonicalPath());
        } catch (IOException e) {
            log.warn("获取数据库规范路径失败: {}", e.getMessage());
        }

        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        return DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url(jdbcUrl)
                .build();
    }

    /**
     * 智能定位 stock.db 文件路径
     */
    private File resolveDatabaseFile() {
        // 0. 支持环境变量指定数据库路径 (例如 Docker / 云原生环境)
        String customPath = System.getenv("STOCK_DB_PATH");
        if (customPath != null && !customPath.isBlank()) {
            return new File(customPath);
        }

        // 1. 如果当前工作目录下已经有 data 目录或 stock.db
        File currentDataDir = new File("data");
        File currentDb = new File(currentDataDir, "stock.db");
        if (currentDb.exists() || currentDataDir.isDirectory()) {
            return currentDb;
        }

        // 2. 如果上一级有 data 目录 (例如在 backend 目录下启动)
        File parentDataDir = new File("../data");
        File parentDb = new File(parentDataDir, "stock.db");
        if (parentDb.exists() || parentDataDir.isDirectory()) {
            return parentDb;
        }

        // 3. 都不存在时，检查当前目录名称
        String currentDirName = new File(System.getProperty("user.dir")).getName();
        if ("backend".equalsIgnoreCase(currentDirName)) {
            return parentDb;
        }

        return currentDb;
    }
}
