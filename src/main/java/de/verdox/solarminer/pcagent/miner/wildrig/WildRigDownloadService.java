package de.verdox.solarminer.pcagent.miner.wildrig;

import de.verdox.solarminer.pcagent.miner.engine.PinnedGpuDownloadService;
import org.springframework.stereotype.Service;

import java.net.URI;

/** Official WildRig NG 1.0.0k assets, pinned to GitHub's release digests. */
@Service
public class WildRigDownloadService extends PinnedGpuDownloadService {
    public static final String VERSION = "1.0.0k";
    public WildRigDownloadService(@org.springframework.beans.factory.annotation.Value(
            "${solarminer.wildrig.install-directory:./solarminer-agent/wildrig-ng}") String directory) {
        super(directory, "WildRig NG", VERSION, "wildrig-ng",
                new Asset(URI.create("https://github.com/andru-kun/wildrig-ng/releases/download/1.0.0k/wildrig-ng-linux-1.0.0k.tar.gz"),
                        15_248_690L, "27ec690adbcdcd0a9aa03b651fccc512899e213529cc8718755c3767be138341", true, false),
                new Asset(URI.create("https://github.com/andru-kun/wildrig-ng/releases/download/1.0.0k/wildrig-ng-windows-1.0.0k.zip"),
                        15_810_898L, "24a63a496414dcacf788a6fbb465868c62dbea82eecc2bc5c9d6693f79c5173f", true, true));
    }
}
