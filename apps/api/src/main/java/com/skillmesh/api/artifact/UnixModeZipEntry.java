package com.skillmesh.api.artifact;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

/**
 * 带 Unix 权限位的 zip entry。
 *
 * 为什么自己写一个：Go 的 {@code archive/zip} 读权限位看的是 central directory
 * 里的 creator version——只有是 UNIX(3) 才从 external attributes 的高 16 位取
 * mode，否则走 DOS 那套，权限位根本读不到（脚本就此装成不可执行）。
 *
 * commons-compress 的 {@code setUnixMode} 只写 external attributes，不改
 * creator version；而 {@code setPlatform} 是 protected，外部调不到。
 * 继承一下就能设。
 */
public final class UnixModeZipEntry extends ZipArchiveEntry {

    public UnixModeZipEntry(String name, int unixMode) {
        super(name);
        setPlatform(PLATFORM_UNIX);
        setUnixMode(unixMode);
    }
}
