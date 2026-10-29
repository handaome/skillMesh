package com.skillmesh.api.skill;

/**
 * slug 的规则。
 *
 * slug 是云端的唯一标识，改名会影响别人已经装的内容，所以定死一套规则：
 * 小写字母、数字、连字符，长度 2-64，以字母或数字开头。
 *
 * 与 CLI 的 skillscan.SlugFromDirName 是同一套：同一份目录在两端算出来的
 * slug 要是同一个，不然用户改了 CLI 装的名字、云端却认成另一个。
 */
public final class SlugRules {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 64;

    private SlugRules() {
    }

    /**
     * 从目录名或 skill 名字推出 slug。
     *
     * 推不出来时抛 IllegalArgumentException——slug 只能显式指定，
     * 猜一个像样的错名字更麻烦。
     */
    public static String fromName(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("名字为空，推不出 slug");
        }
        String slug = raw.trim().toLowerCase(java.util.Locale.ROOT)
            .replace(' ', '-')
            .replace('_', '-');
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("名字推不出 slug");
        }
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!allowed) {
                throw new IllegalArgumentException(
                    "名字里含非法字符 " + c + "，slug 只允许小写字母、数字和连字符");
            }
        }
        if (!Character.isLetterOrDigit(slug.charAt(0))) {
            throw new IllegalArgumentException("slug 必须以字母或数字开头");
        }
        if (slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "slug 长度要在 " + MIN_LENGTH + "-" + MAX_LENGTH + " 之间，实际 " + slug.length());
        }
        return slug;
    }
}
