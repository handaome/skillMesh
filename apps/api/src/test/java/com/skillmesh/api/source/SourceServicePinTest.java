package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 地址固定（DNS rebinding 防护）。
 *
 * 校验与 git 自己的解析之间有时间差，这段时间里 DNS 换成内网地址，
 * 校验就白做了。把刚校验过的地址钉给 git，那段时间差就没有了。
 */
class SourceServicePinTest {

    private final SourceService service =
        new SourceService(new GitHostPolicy("github.com"), 30, 100, 4, "");

    @Test
    void https把主机钉在刚校验过的地址上() {
        GitHostPolicy.Allowed allowed = new GitHostPolicy.Allowed(
            "https://github.com/o/r.git", "github.com", List.of("1.2.3.4", "5.6.7.8"));

        SourceService.Pin pin = service.pin(allowed);

        assertThat(pin.options()).containsExactly(
            "http.curloptResolve=github.com:443:1.2.3.4,github.com:443:5.6.7.8");
        assertThat(pin.sshCommand()).isEmpty();
    }

    @Test
    void ssh换成地址但主机名照旧校验() {
        GitHostPolicy.Allowed allowed = new GitHostPolicy.Allowed(
            "ssh://git@github.com/o/r.git", "github.com", List.of("1.2.3.4"));

        SourceService.Pin pin = service.pin(allowed);

        // HostKeyAlias 让主机密钥照旧按域名校验，不因为换了地址就跳过。
        assertThat(pin.sshCommand())
            .isEqualTo("ssh -o HostName=1.2.3.4 -o HostKeyAlias=github.com");
        assertThat(pin.options()).isEmpty();
    }
}
