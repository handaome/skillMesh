package com.skillmesh.api.scan;

/**
 * 扫描结论。
 *
 * {@code failed} 与 {@code error} 分开：前者是扫出了问题，
 * 后者是扫描器自己没跑起来——两者的处置不一样。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum ScanStatus {

    /** 干净。 */
    passed,

    /** 有命中，但不阻断。 */
    warning,

    /** 扫出了问题，阻断发布。 */
    failed,

    /** 扫描器自己没跑起来。 */
    error
}
