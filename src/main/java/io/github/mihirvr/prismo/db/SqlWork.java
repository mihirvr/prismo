package io.github.mihirvr.prismo.db;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of database work that runs with a connection handed to it by {@link Database}. */
@FunctionalInterface
public interface SqlWork<T> {

    T run(Connection connection) throws SQLException;
}
