package com.skillmesh.api.config;

import io.minio.MinioClient;

/**
 * MinIO 客户端的构造入口。
 *
 * 两处都要一个连到同一个对象存储的客户端：主应用（{@link MinioConfig}）与
 * 独立跑的备份器（{@code com.skillmesh.backup.BackupTool}）。两边读的也是
 * 同一批配置项。构造方式写在一处，两边就不会各写一份、各改一半。
 *
 * 这里是静态工厂而不是 Spring Bean：备份器不是主应用的一部分（它连 Flyway
 * 都刻意绕开），靠注入拿不到客户端，只能自己按参数建。
 */
public final class MinioClients {

    private MinioClients() {}

    /**
     * 建一个 MinIO 客户端。
     *
     * @param endpoint 对象存储地址
     * @param accessKey 访问密钥
     * @param secretKey 私有密钥
     */
    public static MinioClient create(String endpoint, String accessKey, String secretKey) {
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
    }
}
