package com.skillmesh.api.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 幂等键（详细设计 §2.1「可重试写请求：支持 Idempotency-Key」、§4.3 API-001）。
 *
 * 客户端重试时带同一个键：服务端认得出这是同一次请求，把上次的响应原样还回去，
 * 不再建一份资源。没带键就照旧做一遍。
 *
 * 只给「建资源」这类接口用。设备登记、安装登记、地址解析这些天然幂等
 * （同一个键自己就认得出是同一条），不用绕这里。
 */
@Service
public class IdempotencyService {

    /** 键长度的上下限：OpenAPI 里 Idempotency-Key 的 minLength / maxLength。 */
    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 128;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 跑一次带幂等键的写操作。
     *
     * 第一次：做掉，成功后把响应记下来；失败就把键放开——不然重试会被
     * 「上一次还在处理中」挡在门外，而上一次其实压根没成。
     * 重试：上次的响应原样还回去。
     *
     * @param scope 接口作用域，同一个键在不同接口之间不通用
     * @param type  响应类型，重试时按它把存下来的 JSON 读回来
     */
    public <T> T run(CurrentUser user, String scope, String key, Object request,
                     Supplier<T> action, Class<T> type) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        checkKey(key);
        String digest = digest(request);
        // 抢两次：抢不到时去读记录，读不到说明上一次刚好失败、把记录删了——
        // 那是「上一次没成」，不是「有人在处理」，重抢一次。
        for (int attempt = 0; attempt < 2; attempt++) {
            if (claim(user, scope, key, digest)) {
                return doAction(user, scope, key, action);
            }
            Map<String, Object> row = find(user, scope, key);
            if (row == null) {
                continue;
            }
            return replay(row, digest, type);
        }
        throw ApiException.versionConflict("上一次请求还在处理中，请稍后重试");
    }

    /** 第一次做掉：成功记下响应，失败把键放开。 */
    private <T> T doAction(CurrentUser user, String scope, String key, Supplier<T> action) {
        try {
            T result = action.get();
            remember(user, scope, key, result);
            return result;
        } catch (RuntimeException e) {
            // 失败了就放开这个键：不然重试会被「上一次还在处理中」挡在门外，
            // 而上一次其实压根没成。
            release(user, scope, key);
            throw e;
        }
    }

    /** 认领这个键：抢到的人去做，抢不到的是重试。 */
    private boolean claim(CurrentUser user, String scope, String key, String digest) {
        return jdbc.update(
            "insert into idempotency_keys (user_id, scope, idem_key, request_digest) "
                + "values (?, ?, ?, ?) on conflict do nothing",
            user.id(), scope, key, digest) > 0;
    }

    /** 做完了，把响应记下来：下次同一个键就照这个还。 */
    private void remember(CurrentUser user, String scope, String key, Object response) {
        jdbc.update("update idempotency_keys set response_body = ?::jsonb "
                + "where user_id = ? and scope = ? and idem_key = ?",
            write(response), user.id(), scope, key);
    }

    /** 失败：把这行删掉，重试才能再来一次。 */
    private void release(CurrentUser user, String scope, String key) {
        jdbc.update("delete from idempotency_keys "
                + "where user_id = ? and scope = ? and idem_key = ? and response_body is null",
            user.id(), scope, key);
    }

    /** 读上次那条记录；没有就是没有（刚被失败的那次删掉了）。 */
    private Map<String, Object> find(CurrentUser user, String scope, String key) {
        var rows = jdbc.queryForList(
            "select request_digest, response_body::text as body from idempotency_keys "
                + "where user_id = ? and scope = ? and idem_key = ?",
            user.id(), scope, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 重试：把上次的结果原样还回去。 */
    private <T> T replay(Map<String, Object> row, String digest, Class<T> type) {
        if (!digest.equals(row.get("request_digest"))) {
            throw ApiException.versionConflict("同一个幂等键配了不同的请求内容");
        }
        Object body = row.get("body");
        if (body == null) {
            throw ApiException.versionConflict("上一次请求还在处理中，请稍后重试");
        }
        return read(String.valueOf(body), type);
    }

    private void checkKey(String key) {
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "Idempotency-Key 长度要在 " + MIN_KEY_LENGTH + " 到 " + MAX_KEY_LENGTH + " 之间");
        }
    }

    /** 请求体的摘要：同一个键配了不同的内容就是用错了键。 */
    private String digest(Object request) {
        return ContentDigest.sha256(request == null ? "" : write(request));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "序列化请求体失败");
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "读取上次的响应失败");
        }
    }
}
