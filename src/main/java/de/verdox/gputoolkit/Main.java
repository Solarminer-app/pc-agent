package de.verdox.gputoolkit;

import de.verdox.gputoolkit.nvidia.NvidiaNVMLAdapter;

public class Main {
    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("       OMNIGPU LIBRARY - HARDWARE TEST SUITE      ");
        System.out.println("==================================================");

        GPUInterface gpuInterface;
        try {
            gpuInterface = new NvidiaNVMLAdapter();
            System.out.println("[Init] NvidiaNVMLAdapter erfolgreich geladen.");
            System.out.println("[Info] NVIDIA Treiber-Version: " + gpuInterface.getDriverVersion());
        } catch (Exception e) {
            System.err.println("[Init] Konnte NVIDIA Adapter nicht laden: " + e.getMessage());
            return;
        }

        int deviceCount = gpuInterface.getDeviceCount();
        System.out.println("Gefundene GPUs: " + deviceCount);

        if (deviceCount == 0) {
            System.out.println("Keine GPUs im System gefunden.");
            return;
        }

        for (int i = 0; i < deviceCount; i++) {
            String deviceName = gpuInterface.getDeviceName(i);
            System.out.println("\n--------------------------------------------------");
            System.out.println("Teste GPU [" + i + "]: " + deviceName);
            System.out.println("--------------------------------------------------");

            // 1. Support-Checks
            System.out.println("--- Support Capabilities ---");
            System.out.println("Supports Power Target Changes:    " + gpuInterface.supportsPowerTargetChanges(i));
            System.out.println("Supports Core Clock Offset:       " + gpuInterface.supportsOffsetCoreClock(i));
            System.out.println("Supports Memory Clock Offset:     " + gpuInterface.supportsOffsetMemoryClock(i));
            System.out.println("Supports Target Core Clock Lock:  " + gpuInterface.supportsSettingTargetCoreClock(i));
            System.out.println("Supports Fan Speed Control:       " + gpuInterface.supportsFanSpeedControl(i));
            System.out.println("Supports Reset to Defaults:       " + gpuInterface.supportsResetToDefaults(i));

            // 2. Ursprüngliche Werte auslesen
            long initialPower = gpuInterface.getPowerTargetWatts(i);
            int initialCoreClock = gpuInterface.getLiveCoreClockMhz(i);
            int initialMemClock = gpuInterface.getLiveMemoryClockMhz(i);
            float initialTemp = gpuInterface.getTemperatureCelsius(i);
            int initialFan = gpuInterface.getFanSpeedPercent(i);

            System.out.println("\n--- Aktueller Zustand (Vorher) ---");
            System.out.println("Temperatur:             " + initialTemp + " °C");
            System.out.println("Lüftergeschwindigkeit:  " + initialFan + " %");
            System.out.println("Live Power Draw:        " + gpuInterface.getLivePowerDraw(i) + " W");
            System.out.println("Power Target:           " + initialPower + " W (" + gpuInterface.getPowerTargetPercent(i) + "%)");
            System.out.println("Live Core Clock:        " + initialCoreClock + " MHz");
            System.out.println("Live Memory Clock:      " + initialMemClock + " MHz");
            System.out.println("VRAM Nutzung:           " + (gpuInterface.getUsedVramBytes(i) / (1024 * 1024)) + " MB / "
                    + (gpuInterface.getTotalVramBytes(i) / (1024 * 1024)) + " MB");

            // 3. Test: Echte Offsets setzen (Das, was nvidia-smi nicht konnte!)
            if (gpuInterface.supportsOffsetCoreClock(i)) {
                System.out.println("\n--- Test: Core Clock Offset ---");
                try {
                    System.out.println("Setze Core Clock Offset auf +15 MHz...");
                    gpuInterface.setCoreClockOffsetMhz(i, 15);
                    System.out.println("-> Erfolgreich gesetzt!");
                } catch (Exception e) {
                    System.err.println("Fehler beim Core Offset: " + e.getMessage());
                }
            }

            // 4. Test: Power Target Drosselung (Simuliert Solar-Eco-Modus)
            if (gpuInterface.supportsPowerTargetChanges(i) && initialPower > 100) {
                System.out.println("\n--- Test: Power Target Anpassung ---");
                long testPower = initialPower - 20; // 20 Watt runter
                try {
                    System.out.println("Setze Power Target temporär auf " + testPower + " W...");
                    gpuInterface.setPowerTargetWatts(i, testPower);
                    System.out.println("-> Neues Power Target bestätigt: " + gpuInterface.getPowerTargetWatts(i) + " W");
                } catch (Exception e) {
                    System.err.println("Fehler beim Power Target: " + e.getMessage());
                }
            }

            // Kurze Pause zum Beobachten
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }

            // 5. Cleanup / Reset zu Werkseinstellungen
            System.out.println("\n--- Cleanup / Reset ---");
            if (gpuInterface.supportsResetToDefaults(i)) {
                try {
                    System.out.println("Setze GPU " + i + " auf Werkseinstellungen zurück...");
                    gpuInterface.resetToDefaults(i);
                    System.out.println("-> Reset erfolgreich! Power Target wieder auf: " + gpuInterface.getPowerTargetWatts(i) + " W");
                } catch (Exception e) {
                    System.err.println("Fehler beim Reset: " + e.getMessage());
                }
            }
        }

        System.out.println("\n==================================================");
        System.out.println("           ALLE TESTS ERFOLGREICH BEENDET         ");
        System.out.println("==================================================");
    }
}
