package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleToolsReadServiceTest {
    @Test void coldReadReturnsBeforeProducerAndProjectionBoundsRows() throws Exception {
        var tools=mock(ConsoleToolsService.class); var mapper=new ObjectMapper(); var service=new ConsoleToolsReadService(tools,mapper);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(tools.collect("hardware",null)).thenAnswer(invocation->{entered.countDown(); release.await(2,TimeUnit.SECONDS);
            return Map.of("workers",IntStream.range(0,1000).mapToObj(index->Map.of("deviceId","gpu-"+index)).toList());});
        try {
            var cold=service.read("hardware",""); assertThat(cold.payload()).isNull(); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            ConsoleToolsReadService.Snapshot ready;
            do {ready=service.read("hardware","");if(ready.payload()==null)Thread.sleep(5);} while(ready.payload()==null&&System.nanoTime()<deadline);
            assertThat(ready.payload().path("workers").size()).isEqualTo(200);
            assertThat(ready.payload().path("workersTotal").asInt()).isEqualTo(1000);
            assertThat(service.read("hardware","gpu-9").payload().path("workers").size()).isEqualTo(1);
            assertThat(service.read("hardware","gpu-9").payload().path("workers").get(0).path("deviceId").asText()).isEqualTo("gpu-9");
        } finally {release.countDown();service.close();}
    }
    @Test void failedRefreshPreservesSourceTimestampAndReportsFailure() throws Exception {
        var tools=mock(ConsoleToolsService.class); var service=new ConsoleToolsReadService(tools,new ObjectMapper());
        when(tools.collect("updates",null)).thenReturn(Map.of("currentTag","v1"));
        try {
            service.read("updates",null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            ConsoleToolsReadService.Snapshot first;
            do {first=service.read("updates",null);if(first.payload()==null)Thread.sleep(5);} while(first.payload()==null&&System.nanoTime()<deadline);
            var timestamp=first.collectedAt();
            when(tools.collect("updates",null)).thenThrow(new IllegalStateException("unavailable")); service.refresh();
            ConsoleToolsReadService.Snapshot failed;
            do {failed=service.read("updates",null);if(failed.error()==null)Thread.sleep(5);} while(failed.error()==null&&System.nanoTime()<deadline);
            assertThat(failed.collectedAt()).isEqualTo(timestamp);assertThat(failed.error()).isEqualTo("tool_load_failed");
            assertThat(failed.payload().path("currentTag").asText()).isEqualTo("v1");
        } finally {service.close();}
    }
    @Test void largeLiveSessionsAreBoundedWithoutLosingOriginalSampleCounts() throws Exception {
        var tools=mock(ConsoleToolsService.class);var service=new ConsoleToolsReadService(tools,new ObjectMapper());
        var points=IntStream.range(0,12).mapToObj(index->Map.of("index",index,"hashrateHs",1000)).toList();
        var series=IntStream.range(0,1000).mapToObj(index->Map.of("deviceId","gpu-"+index,"points",points)).toList();
        when(tools.collect("measurements",null)).thenReturn(Map.of("benchmark",Map.of("results",series,"liveSeries",series),"sweep",Map.of("runs",series)));
        try {
            service.read("measurements",null);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            ConsoleToolsReadService.Snapshot ready;
            do {ready=service.read("measurements",null);if(ready.payload()==null)Thread.sleep(5);}while(ready.payload()==null&&System.nanoTime()<deadline);
            var benchmark=ready.payload().path("benchmark");
            assertThat(benchmark.path("results").size()).isEqualTo(50);
            assertThat(benchmark.path("liveSeries").size()).isEqualTo(50);
            assertThat(benchmark.path("liveSeries").get(0).path("points").size()).isEqualTo(1);
            assertThat(benchmark.path("liveSeries").get(0).path("sampleCount").asInt()).isEqualTo(12);
            assertThat(ready.payload().path("sweep").path("runs").size()).isEqualTo(50);
        }finally{service.close();}
    }
    @Test void unknownSectionsNeverScheduleDomainWork() {
        var tools=mock(ConsoleToolsService.class);var service=new ConsoleToolsReadService(tools,new ObjectMapper());
        try {assertThatThrownBy(()->service.read("../other",null)).hasMessage("tool_invalid_request");verifyNoInteractions(tools);}finally{service.close();}
    }
}
