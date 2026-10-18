package de.verdox.solarminer.pcagent.miner.xmr.download;

import java.util.List;

public record XMRigRelease(
        String url,
        String version,
        Long ts,
        List<XMRigAssetObject> assets
) {}
