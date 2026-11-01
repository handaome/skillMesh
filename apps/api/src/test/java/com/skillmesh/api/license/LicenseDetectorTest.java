package com.skillmesh.api.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 许可证识别：认出是哪一份，认不出就说认不出。
 */
class LicenseDetectorTest {

    private static final String MIT = """
        MIT License

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction.
        """;

    private static final String APACHE = """
        Apache License
                           Version 2.0, January 2004
        http://www.apache.org/licenses/
        """;

    private static final String GPL3 = """
        GNU GENERAL PUBLIC LICENSE
                           Version 3, 29 June 2007
        """;

    @Test
    void 认出常见的许可证() {
        assertThat(LicenseDetector.detect(MIT)).isEqualTo("MIT");
        assertThat(LicenseDetector.detect(APACHE)).isEqualTo("Apache-2.0");
        assertThat(LicenseDetector.detect(GPL3)).isEqualTo("GPL-3.0");
    }

    @Test
    void 认不出来就返回空() {
        assertThat(LicenseDetector.detect("随便一段文字")).isNull();
        assertThat(LicenseDetector.detect("")).isNull();
    }

    @Test
    void 从归档里找许可证文件() {
        byte[] zip = zipWith("LICENSE", MIT);
        assertThat(LicenseDetector.detectInZip(zip)).isEqualTo("MIT");
        assertThat(LicenseDetector.detectInZip(zipWith("README.md", MIT))).isNull();
    }

    /** 造一个只有一项的 zip。 */
    private static byte[] zipWith(String name, String content) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream out = new ZipOutputStream(buffer)) {
                out.putNextEntry(new ZipEntry(name));
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
