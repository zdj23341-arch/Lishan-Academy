package com.wechat.jxsc.commerce;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

/** Test-only scheduling at real JDBC boundaries; every statement still executes against MySQL. */
@TestConfiguration
class FinanceSqlGate {
    interface Observer {
        void visit(String sql, boolean after) throws Exception;
    }

    static volatile Observer observer = (sql, after) -> {};

    static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("JDBC barrier timed out");
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Connection wrap(Connection connection) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(connection, method, args);
                            if (result instanceof PreparedStatement statement
                                    && args[0] instanceof String sql) {
                                return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class},
                                        (p, m, a) -> {
                                            boolean execute = m.getName().startsWith("execute");
                                            if (execute) observer.visit(sql, false);
                                            Object value = invoke(statement, m, a);
                                            if (execute) observer.visit(sql, true);
                                            return value;
                                        });
                            }
                            return result;
                        });
    }

    @Bean
    static BeanPostProcessor financeJdbcScheduling() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source)) return bean;
                return new DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        return wrap(super.getConnection());
                    }

                    @Override
                    public Connection getConnection(String user, String password)
                            throws SQLException {
                        return wrap(super.getConnection(user, password));
                    }
                };
            }
        };
    }
}
