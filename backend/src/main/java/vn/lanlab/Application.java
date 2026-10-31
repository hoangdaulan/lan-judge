package vn.lanlab;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.server.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@SpringBootApplication
public class Application {
  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "api" : args[0];
    if (mode.startsWith("--")) mode = "api";
    ObjectMapper mapper =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    if (mode.equals("lannet")) {
      Lan.cli(Arrays.copyOfRange(args, 1, args.length), mapper);
      return;
    }
    if (mode.equals("wsprobe") || mode.equals("loadtest")) {
      ClientTools.run(mode, Arrays.copyOfRange(args, 1, args.length), mapper);
      return;
    }
    if (!Set.of("api", "worker", "migrate", "seed").contains(mode))
      throw new IllegalArgumentException("api|worker|migrate|seed|lannet|wsprobe|loadtest");
    Config cfg = new Config(System.getenv());
    Map<String, Object> props = new HashMap<>(cfg.datasource());
    props.put("lan.mode", mode);
    cfg.slots();
    cfg.lease();
    cfg.duration("WORKER_POLL_INTERVAL", "1s");
    if (mode.equals("api")) {
      if (cfg.get("JWT_SECRET").getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
        throw new IllegalArgumentException(
            "JWT_SECRET is required and must be at least 32 characters");
      var addr = Config.address(cfg.get("HTTP_ADDR", ":8080"));
      props.put("server.address", addr.getAddress().getHostAddress());
      props.put("server.port", addr.getPort());
    }
    SpringApplication app = new SpringApplication(Application.class);
    app.setDefaultProperties(props);
    if (!mode.equals("api")) app.setWebApplicationType(WebApplicationType.NONE);
    var context = app.run();
    if (mode.equals("migrate")) {
      try {
        new Migrations(context.getBean(Db.class)).run(args.length > 1 ? args[1] : "up");
      } finally {
        context.close();
      }
    }
    if (mode.equals("seed")) {
      try {
        var auth = context.getBean(Auth.class);
        String code = Auth.code(cfg.get("SEED_TEACHER_CODE")),
            fullname = cfg.get("SEED_TEACHER_FULLNAME");
        if (code.isEmpty() || code.length() > 50 || fullname.isBlank())
          throw new IllegalArgumentException(
              "set SEED_TEACHER_CODE, SEED_TEACHER_FULLNAME and SEED_TEACHER_PASSWORD");
        var db = context.getBean(Db.class);
        var row =
            db.one(
                "INSERT INTO users(code,fullname,role,password_hash) VALUES($1,$2,'teacher',$3) ON"
                    + " CONFLICT(code) DO NOTHING RETURNING id",
                code,
                fullname,
                auth.hash(cfg.get("SEED_TEACHER_PASSWORD")));
        if (row == null) throw ApiError.conflict("code already exists");
        System.out.println("created teacher " + code);
      } finally {
        context.close();
      }
    }
  }

  @Bean
  Config config() {
    return new Config(System.getenv());
  }

  @Bean
  Db db(DataSource source) {
    return new Db(source);
  }

  @Bean
  Auth auth(Db db, Config cfg, ObjectMapper mapper) {
    return new Auth(db, cfg, mapper);
  }

  @Bean
  Storage storage(Config cfg) {
    return new Storage(cfg.data());
  }

  @Bean
  Students students(Db db, Auth auth) {
    return new Students(db, auth);
  }

  @Bean
  Exams exams(Db db) {
    return new Exams(db);
  }

  @Bean
  Problems problems(Db db, Exams exams, Storage storage) {
    return new Problems(db, exams, storage);
  }

  @Bean
  Submissions submissions(Db db, Exams exams, Storage storage) {
    return new Submissions(db, exams, storage);
  }

  @Bean
  Board board(Db db, Exams exams, Submissions subs) {
    return new Board(db, exams, subs);
  }

  @Bean
  JudgeQueue queue(Db db, Config cfg) {
    return new JudgeQueue(db, cfg);
  }

  @Bean
  @ConditionalOnProperty(name = "lan.mode", havingValue = "worker")
  CppJudge cppJudge(Config cfg, Storage storage, ObjectMapper json) throws Exception {
    return new CppJudge(cfg, storage, json);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "lan.mode", havingValue = "worker")
  Worker worker(Config cfg, JudgeQueue queue, CppJudge judge, ObjectMapper json) {
    return new Worker(cfg, queue, judge, json);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "lan.mode", havingValue = "worker")
  Events workerEvents(Config cfg, DataSource source, Worker worker) {
    return cfg.get("WORKER_LISTEN").equals("false")
        ? null
        : new Events(source, p -> worker.wake(), worker::wake);
  }
}

@Configuration
@ConditionalOnWebApplication
@EnableWebSocket
class WebConfiguration implements WebSocketConfigurer, WebMvcConfigurer {
  private final Realtime realtime;
  private final Config cfg;

  WebConfiguration(Realtime realtime, Config cfg) {
    this.realtime = realtime;
    this.cfg = cfg;
  }

  @Bean
  static Lan lan(Config cfg, ObjectMapper json) throws Exception {
    return new Lan(cfg, json);
  }

  @Bean
  static Realtime realtime(
      Auth auth, Exams exams, Submissions subs, Board board, Db db, Lan lan, ObjectMapper mapper) {
    return new Realtime(auth, exams, subs, board, db, lan, mapper);
  }

  @Bean
  static Dispatch dispatcher(Config cfg, JudgeQueue queue, ObjectMapper mapper) throws Exception {
    return cfg.get("DISPATCH_ADDR").isEmpty() ? null : new Dispatch(cfg, queue, mapper);
  }

  @Bean
  static Events apiEvents(
      DataSource source,
      Realtime realtime,
      org.springframework.beans.factory.ObjectProvider<Dispatch> dispatch) {
    return new Events(
        source,
        p -> {
          realtime.event(p);
          var d = dispatch.getIfAvailable();
          if (d != null) d.wake();
        },
        realtime::resync);
  }

  @Bean
  static FilterRegistrationBean<Api.AccessFilter> access(
      Auth auth, ObjectMapper mapper, Config cfg) {
    var filter = new FilterRegistrationBean<>(new Api.AccessFilter(auth, mapper, cfg));
    filter.setOrder(-100);
    filter.addUrlPatterns("/*");
    return filter;
  }

  @Bean
  static WebServerFactoryCustomizer<TomcatServletWebServerFactory> https(Config cfg) {
    return factory -> {
      if (cfg.get("HTTPS_ADDR").isEmpty()) return;
      try {
        Path dir = Tls.ensure(cfg);
        var address = Config.address(cfg.get("HTTPS_ADDR"));
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(address.getPort());
        connector.setProperty("address", address.getAddress().getHostAddress());
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");
        SSLHostConfig host = new SSLHostConfig();
        SSLHostConfigCertificate certificate =
            new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.EC);
        certificate.setCertificateFile(dir.resolve("cert.pem").toString());
        certificate.setCertificateKeyFile(dir.resolve("key.pem").toString());
        host.addCertificate(certificate);
        connector.addSslHostConfig(host);
        factory.addAdditionalTomcatConnectors(connector);
      } catch (Exception e) {
        throw new IllegalStateException("HTTPS setup failed", e);
      }
    };
  }

  @Bean
  static AutoCloseable cleanup(Submissions subs) {
    var timer =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "orphan-cleanup");
              t.setDaemon(true);
              return t;
            });
    timer.scheduleWithFixedDelay(
        () -> {
          try {
            subs.cleanup();
          } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(WebConfiguration.class)
                .warn("orphan cleanup failed", e);
          }
        },
        30,
        30,
        java.util.concurrent.TimeUnit.MINUTES);
    return timer::shutdownNow;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(org.springframework.http.CacheControl.noCache());
    registry
        .addResourceHandler("/swagger/**")
        .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/5.27.1/");
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/").setViewName("forward:/index.html");
    registry.addRedirectViewController("/swagger", "/swagger/index.html");
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(realtime, "/api/v1/ws")
        .addInterceptors(
            new HandshakeInterceptor() {
              @Override
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  org.springframework.web.socket.WebSocketHandler handler,
                  Map<String, Object> attrs) {
                String origin = request.getHeaders().getOrigin();
                if (origin == null) return true;
                try {
                  String authority = java.net.URI.create(origin).getRawAuthority();
                  if (authority == null) throw new IllegalArgumentException();
                  String host = request.getHeaders().getFirst("Host");
                  boolean allowed =
                      authority.equalsIgnoreCase(host)
                          || cfg.list("WS_ALLOWED_ORIGINS").stream()
                              .anyMatch(
                                  pattern ->
                                      org.springframework.util.PatternMatchUtils.simpleMatch(
                                          pattern.toLowerCase(Locale.ROOT),
                                          authority.toLowerCase(Locale.ROOT)));
                  if (allowed) return true;
                } catch (Exception ignored) {
                }
                response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
                return false;
              }

              @Override
              public void afterHandshake(
                  ServerHttpRequest r,
                  ServerHttpResponse s,
                  org.springframework.web.socket.WebSocketHandler h,
                  Exception e) {}
            })
        .setAllowedOriginPatterns("*");
  }
}
