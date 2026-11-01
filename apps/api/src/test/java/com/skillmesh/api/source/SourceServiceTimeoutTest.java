package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * git 命令挂住时，超时上限必须真的生效。
 *
 * 之前是先 readAllBytes() 再 waitFor(timeout)：进程不退出、流就不关，
 * 那一行会一直阻塞，配置里的 30 秒上限根本走不到——卡住的 git 能一直占着线程。
 */
class SourceServiceTimeoutTest {

    @Test
    void 进程卡住时按时限中断() {
        SourceService service = new SourceService(null, 1, 10, 1, "");
        long started = System.nanoTime();

        assertThatThrownBy(() -> service.run(blockingCommand()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("超时");

        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void 正常退出时返回输出() throws Exception {
        SourceService service = new SourceService(null, 30, 10, 1, "");
        assertThat(service.run(List.of("git", "--version"))).contains("git version");
    }

    /** 一个会挂着不退出的进程。 */
    private List<String> blockingCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
            ? List.of("cmd", "/c", "ping -n 30 127.0.0.1 > nul")
            : List.of("sleep", "30");
    }
}
