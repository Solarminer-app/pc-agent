package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleToolJobsTest {
    @Test void duplicateRequestIdReturnsTheSameJobWithoutRepeatingAWrite() throws Exception {
        var tools=mock(ConsoleToolsService.class); var json=new ObjectMapper();
        {
            var jobs=new ConsoleToolJobs(tools);
            try {
                var request=new ConsoleToolJobs.Request("aaaaaaaa-bbbb-cccc-dddd","update_check",json.readTree("{}"));
                var first=jobs.submit(request); var second=jobs.submit(request);
                assertThat(first.id()).isEqualTo(second.id());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!jobs.get(first.id()).orElseThrow().state().equals("succeeded") && System.nanoTime()<deadline) Thread.sleep(5);
                assertThat(jobs.get(first.id()).orElseThrow().state()).isEqualTo("succeeded");
                verify(tools,times(1)).execute("update_check",request.arguments());
                assertThatThrownBy(()->jobs.submit(new ConsoleToolJobs.Request(request.id(),"update_start",request.arguments()))).hasMessage("tool_request_id_conflict");
            } finally { jobs.close(); }
        }
    }
    @Test void invalidRequestIsRejectedBeforeDomainAccess() throws Exception {
        var tools=mock(ConsoleToolsService.class); var jobs=new ConsoleToolJobs(tools); var object=new ObjectMapper().readTree("{}");
        try {
            assertThatThrownBy(()->jobs.submit(new ConsoleToolJobs.Request("valid-id-123456789",null,object))).hasMessage("tool_invalid_request");
            assertThatThrownBy(()->jobs.submit(new ConsoleToolJobs.Request("bad","update_start",object))).hasMessage("tool_invalid_request");
            verifyNoInteractions(tools);
        } finally {jobs.close();}
    }
}
