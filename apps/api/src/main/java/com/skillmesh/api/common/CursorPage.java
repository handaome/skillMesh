package com.skillmesh.api.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.function.Function;

/**
 * 游标分页的一页：数据 + 下一页游标 + 还有没有更多。
 *
 * 所有分页端点的形状都一样（items / next_cursor / has_more），
 * 差别只在游标怎么算——所以形状只在这里定义一次，各端点自己算自己的游标。
 *
 * 客户端拿到的 next 游标要能原样传回下一次请求，
 * 所以各端点的游标编解码必须用同一套（{@link CursorCodec}）。
 */
public record CursorPage<T>(
        List<T> items,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_more") boolean hasMore) {

    /**
     * 多取一条判断还有没有下一页，再把这一页与游标包起来。
     *
     * 查的时候多取一条：返回条数比 size 多就说明还有下一页。
     * 这比再查一次 count(*) 便宜，也避免了两次查询之间数据变动的窗口。
     *
     * @param rows  多取了一条的查询结果（size + 1 条以内）
     * @param size  这一页要几条
     * @param nextOf 由本页最后一条算出下一页游标
     */
    public static <T> CursorPage<T> of(List<T> rows, int size, Function<T, String> nextOf) {
        boolean hasMore = rows.size() > size;
        List<T> page = hasMore ? rows.subList(0, size) : rows;
        String next = null;
        if (hasMore && !page.isEmpty()) {
            next = nextOf.apply(page.get(page.size() - 1));
        }
        return new CursorPage<>(page, next, hasMore);
    }

    /**
     * 把这一页的条目换成对外的形状，游标与「还有更多」原样带着。
     *
     * 查出来的行常是实体，对外要的是响应体——游标是按实体算的，
     * 所以换条目时不能重算游标。
     */
    public <R> CursorPage<R> map(Function<List<T>, List<R>> mapper) {
        return new CursorPage<>(mapper.apply(items), nextCursor, hasMore);
    }
}
