package com.skillmesh.api.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * 数一条路径上跑了几次查询。
 *
 * 用来钉住「别把 N+1 放回来」：关联数据批量查齐之后，
 * 页面发起的查询条数应该跟条目数无关。
 */
public class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** 跑一遍动作，返回它执行了多少次查询。 */
    public long count(Action action) throws Exception {
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            action.run();
            return statistics.getQueryExecutionCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    /** 允许抛异常的动作——MockMvc 的调用签名带 checked 异常。 */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }
}
