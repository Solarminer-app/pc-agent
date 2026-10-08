package de.verdox.solarminer.pcagent.xmr.download;

public record XMRigAssetObject(
        String os,
        String id,
        String name,
        String url,
        long size,
        long ts,
        String hash,
        String cuda) {
}
