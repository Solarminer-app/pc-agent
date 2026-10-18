package de.verdox.solarminer.pcagent.miner.peak;

import de.verdox.solarminer.pcagent.miner.engine.PinnedGpuDownloadService;
import org.springframework.stereotype.Service;

import java.net.URI;

/** Official PeakMiner v2.19.1 assets, pinned to GitHub's release digests. */
@Service
public class PeakDownloadService extends PinnedGpuDownloadService {
    public PeakDownloadService(@org.springframework.beans.factory.annotation.Value(
            "${solarminer.peak.install-directory:./solarminer-agent/peakminer}") String directory) {
        super(directory, "PeakMiner", "2.19.1", "peakminer",
                new Asset(URI.create("https://github.com/peakminer/peakminer/releases/download/v2.19.1/peakminer-2.19.1-linux-x86_64"),
                        48_577_340L, "99d19b100ad136f11ad07f593dbd6eca8a6bd5631f9b9fb519576a1a87aa9ab1", false, false),
                new Asset(URI.create("https://github.com/peakminer/peakminer/releases/download/v2.19.1/peakminer-2.19.1-windows-x86_64.zip"),
                        52_800_629L, "03cfed911cc36fc70cc9b29f5c904dfc55dd3444b1631ae387ff9441b1ae9631", true, true));
    }
}
