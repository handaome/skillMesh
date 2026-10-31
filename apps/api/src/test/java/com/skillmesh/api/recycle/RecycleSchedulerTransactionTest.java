package com.skillmesh.api.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 定时清理要在一个事务里跑。
 *
 * 删过期条目是 {@code @Modifying} 查询：没有事务，Spring Data 直接抛
 * {@code TransactionRequiredException}。而 {@code @Scheduled} 方法上的
 * {@code @Transactional} 能不能生效，取决于调度框架是不是调在代理上——
 * 这一条错了，回收站清理每次到点就报错，而看回收站时顺手清理的那一步照常工作，
 * 看起来一切正常，只是定时任务从来没清过东西。
 *
 * 这里不起 Spring 上下文、不连库：照着 Spring 的做法手工搭一遍代理，
 * 用框架自己的类判断注解与拦截器。
 */
class RecycleSchedulerTransactionTest {

    /** 记下事务开了几次。 */
    private static final class CountingTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicInteger begun = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    @Test
    void 清理方法带事务注解且经代理调用时事务会开() throws Exception {
        var method = RecycleScheduler.class.getMethod("clean");

        // Spring 认这个注解：它会为带 @Transactional 的方法套上事务拦截器。
        assertThat(new AnnotationTransactionAttributeSource()
            .getTransactionAttribute(method, RecycleScheduler.class)).isNotNull();

        // 照 Spring 的做法把 bean 包一层：拦截器在代理上。
        CountingTransactionManager tx = new CountingTransactionManager();
        var target = new RecycleScheduler(mock(RecycleFileRepository.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));

        var proxy = (RecycleScheduler) factory.getProxy();
        assertThat(AopUtils.isAopProxy(proxy)).isTrue();

        // 调度框架调的正是代理上的方法：事务会被打开。
        method.invoke(proxy);

        assertThat(tx.begun.get()).isEqualTo(1);
    }
}
