package com.skillmesh.api.skill;

/**
 * 来源的许可证状态。
 *
 * 只是线索，不是判决：识别出来是 MIT 不等于能再分发，
 * 「能不能发」仍要人判断（详细设计的取舍清单里写着这一条）。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum LicenseStatus {

    /** 没识别出来。 */
    unknown,

    /** 识别出来了，但没判能不能用。 */
    detected,

    /** 判过了，能用。 */
    approved,

    /** 有限制（换个名字、附声明之类）。 */
    restricted,

    /** 不能用。 */
    incompatible
}
