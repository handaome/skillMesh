package com.skillmesh.api.artifact;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Semaphore;

/**
 * 关掉时释放并发名额的输入流。
 *
 * 名额跟着流走：服务端把响应写完就会关掉流
 * （ResourceHttpMessageConverter 在 finally 里关），
 * 名额随流一起还回去，不会因为客户端提前断开而漏掉。
 */
final class ReleasingInputStream extends FilterInputStream {

    private final Semaphore slots;
    private boolean released;

    ReleasingInputStream(InputStream in, Semaphore slots) {
        super(in);
        this.slots = slots;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            release();
        }
    }

    private void release() {
        if (!released) {
            released = true;
            slots.release();
        }
    }
}
