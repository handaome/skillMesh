package com.skillmesh.api.source;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务端拉 Git 之前的主机闸门（设计文档 §7.3、§14）。
 *
 * 两条红线：只允许显式放行的主机；解析出来的地址不能落在内网/回环/链路本地。
 * 后者防的是「白名单主机名被解析到 169.254.169.254」这类绕过——
 * 光校验主机名不够，DNS 说什么才算数。
 */
@Component
public class GitHostPolicy {

    private final List<String> allowedHosts;

    public GitHostPolicy(@Value("${skillmesh.git.allowed-hosts:github.com,gitlab.com,gitee.com}")
                         String allowed) {
        this.allowedHosts = Arrays.stream(allowed.split(","))
            .map(host -> host.trim().toLowerCase(Locale.ROOT))
            .filter(host -> !host.isEmpty())
            .toList();
    }

    /**
     * 校验仓库地址能不能拉。通过就返回规范化后的 https 地址。
     *
     * 只接受 https 和 ssh——http 明文、file:// 读本地文件、其它协议一律拒。
     */
    public String requireAllowed(String rawUrl) {
        URI uri = parse(rawUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"ssh".equals(scheme)) {
            throw forbidden("只允许 https / ssh 仓库地址，实际为 " + (scheme.isEmpty() ? "无协议" : scheme));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw forbidden("仓库地址里没有主机名");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw forbidden("主机不在允许列表里：" + host + "（允许：" + String.join(", ", allowedHosts) + "）");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            // ssh://git@host/... 的 git 是用户名；带密码的一律拒。
            if (uri.getUserInfo().contains(":")) {
                throw forbidden("仓库地址不得内嵌密码");
            }
        }
        requirePublicAddress(host);
        return rawUrl.trim();
    }

    /** 解析主机名，任一地址落在内网就拒。 */
    private void requirePublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw forbidden("主机名解析失败：" + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || isUniqueLocal(address)) {
                throw forbidden("主机指向内网地址，拒绝（SSRF 防护）：" + host);
            }
        }
    }

    /** 100.64.0.0/10 这段运营商级 NAT 不在 isSiteLocalAddress 覆盖范围内。 */
    private boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100
            && (bytes[1] & 0xc0) == 64;
    }

    private URI parse(String rawUrl) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw forbidden("仓库地址为空");
        }
        try {
            return URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw forbidden("仓库地址格式不对");
        }
    }

    private ApiException forbidden(String message) {
        return new ApiException(400, ErrorCodes.VALIDATION_FAILED, message);
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }
}
