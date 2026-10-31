package vn.lanlab;

import static vn.lanlab.Db.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

public final class Migrations {
  private static final String[] FILES = {"00001_init.sql", "00002_users_date_of_birth.sql"};
  private final Db db;

  public Migrations(Db db) {
    this.db = db;
  }

  public void run(String command) {
    db.tx(
        () -> {
          db.one("SELECT pg_advisory_xact_lock(7611060317)");
          db.jdbc.execute(
              "CREATE TABLE IF NOT EXISTS goose_db_version(id serial PRIMARY KEY,version_id bigint"
                  + " NOT NULL,is_applied boolean NOT NULL,tstamp timestamp NOT NULL DEFAULT"
                  + " now())");
          if (num(db.one("SELECT count(*) AS n FROM goose_db_version"), "n") == 0)
            db.update("INSERT INTO goose_db_version(version_id,is_applied) VALUES(0,true)");
          Set<Integer> applied = new TreeSet<>();
          for (var row :
              db.rows(
                  "SELECT DISTINCT ON(version_id) version_id,is_applied FROM goose_db_version ORDER"
                      + " BY version_id,id DESC"))
            if (Boolean.TRUE.equals(row.get("is_applied")))
              applied.add((int) num(row, "version_id"));
          if (command.equals("status")) {
            for (int i = 1; i <= FILES.length; i++)
              System.out.println(
                  FILES[i - 1] + ": " + (applied.contains(i) ? "applied" : "pending"));
            return null;
          }
          if (command.equals("down")) {
            int version = applied.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (version > 0) apply(version, false);
            return null;
          }
          if (!command.equals("up")) throw new IllegalArgumentException("migrate up|down|status");
          for (int i = 1; i <= FILES.length; i++) if (!applied.contains(i)) apply(i, true);
          return null;
        });
  }

  private void apply(int version, boolean up) {
    try {
      String sql =
          new ClassPathResource("migrations/" + FILES[version - 1])
              .getContentAsString(StandardCharsets.UTF_8);
      int a = sql.indexOf("-- +goose Up"), b = sql.indexOf("-- +goose Down");
      String body = up ? sql.substring(a + 12, b) : sql.substring(b + 14);
      db.jdbc.execute(body);
      db.update("INSERT INTO goose_db_version(version_id,is_applied) VALUES($1,$2)", version, up);
      System.out.println((up ? "applied " : "reverted ") + FILES[version - 1]);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
