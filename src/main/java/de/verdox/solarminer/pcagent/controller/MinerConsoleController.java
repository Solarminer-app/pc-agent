package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/agent/local/console")
public class MinerConsoleController {
    private final MinerConsoleService consoles;

    public MinerConsoleController(MinerConsoleService consoles) {
        this.consoles = consoles;
    }

    @GetMapping("/{miner}")
    public ResponseEntity<MinerConsoleService.ConsoleChunk> read(@PathVariable String miner,
                                                                   @RequestParam(defaultValue = "0") long offset)
            throws IOException {
        if (!known(miner)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(consoles.read(miner, offset));
    }

    @GetMapping("/{miner}/download")
    public ResponseEntity<Resource> download(@PathVariable String miner) {
        if (!known(miner)) return ResponseEntity.notFound().build();
        Path file = consoles.file(miner);
        if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        String name = file.getFileName().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(file));
    }

    private static boolean known(String miner) {
        return "monero".equals(miner) || "pearl".equals(miner)
                || "ravencoin".equals(miner) || "ethereumclassic".equals(miner) || "decred".equals(miner) || "quantus".equals(miner)
                || miner.matches("(pearl|ravencoin|ethereumclassic|decred|quantus)-(NVIDIA|AMD)-[0-9]{1,5}");
    }
}
