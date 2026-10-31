package vn.lanlab;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "RUN_SANDBOX_TESTS", matches = "true")
class SandboxTest {
  @TempDir Path temp;

  private JudgeQueue.Outcome judge(String source, int time, int memory) throws Exception {
    Path data = temp.resolve("data"), work = temp.resolve("work");
    Files.createDirectories(data);
    Files.setPosixFilePermissions(
        temp, java.nio.file.attribute.PosixFilePermissions.fromString("rwx--x--x"));
    Files.createDirectories(data.resolve("submissions"));
    Files.writeString(data.resolve("submissions/main.cpp"), source);
    Files.writeString(data.resolve("1.in"), "1 2\n");
    Files.writeString(data.resolve("1.out"), "3\n");
    var cfg = new Config(Map.of("DATA_DIR", data.toString(), "JUDGE_WORK_DIR", work.toString()));
    var judge = new CppJudge(cfg, new Storage(data), new ObjectMapper().findAndRegisterModules());
    assertTrue(judge.enabled());
    var job = new JudgeQueue.Job();
    job.JobID = 1;
    job.Attempt = 1;
    job.SourcePath = "submissions/main.cpp";
    job.TimeLimitMs = time;
    job.MemoryLimitMb = memory;
    var tc = new JudgeQueue.TestCase();
    tc.ID = 1;
    tc.InputPath = "1.in";
    tc.OutputPath = "1.out";
    job.Tests = List.of(tc);
    return judge.judge(job);
  }

  @Test
  void acceptsCpp17AndMeasuresResources() throws Exception {
    var result =
        judge(
            "#include <cstdio>\nint main(){int a,b;scanf(\"%d%d\",&a,&b);printf(\"%d\\n\",a+b);}",
            1000, 256);
    assertEquals("AC", result.Verdict);
    assertEquals(1, result.PassedTests);
    assertNotNull(result.Tests.get(0).MemoryKB);
  }

  @Test
  void compileErrorsRemainCe() throws Exception {
    assertEquals("CE", judge("invalid c++", 1000, 256).Verdict);
  }

  @Test
  void wrongAnswerAndRuntimeError() throws Exception {
    assertEquals("WA", judge("int main(){return 0;}", 1000, 256).Verdict);
    assertEquals("RE", judge("int main(){return 42;}", 1000, 256).Verdict);
  }

  @Test
  void cpuLimitIsEnforced() throws Exception {
    assertEquals("TLE", judge("int main(){while(true){}}", 100, 256).Verdict);
  }

  @Test
  void memoryLimitIsEnforced() throws Exception {
    assertEquals(
        "MLE",
        judge(
                "#include <new>\nint main(){volatile char *p=new char[512L*1024*1024];p[0]=1;}",
                1000,
                64)
            .Verdict);
  }

  @Test
  void runnerCannotReadDataOrUseNetworking() throws Exception {
    String source =
        "#include <cstdio>\n"
            + "#include <unistd.h>\n"
            + "#include <sys/socket.h>\n"
            + "#include <arpa/inet.h>\n"
            + "#include <ifaddrs.h>\n#include <net/if.h>\n"
            + "int main(){if(geteuid()==0)return 1;FILE*f=fopen(\""
            + temp.resolve("data/1.out")
            + "\",\"r\");if(f)return 2;ifaddrs*ifs=nullptr;if(getifaddrs(&ifs)!=0)return"
            + " 4;for(auto*p=ifs;p;p=p->ifa_next)if(p->ifa_flags&IFF_UP)return"
            + " 5;freeifaddrs(ifs);int s=socket(AF_INET,SOCK_STREAM,0);sockaddr_in"
            + " a{};a.sin_family=AF_INET;a.sin_port=htons(80);inet_pton(AF_INET,\"1.1.1.1\",&a.sin_addr);if(connect(s,(sockaddr*)&a,sizeof(a))==0)return"
            + " 3;puts(\"3\");}";
    assertEquals("AC", judge(source, 1000, 256).Verdict);
  }
}
