> Aktueller Implementierungsstand / Current implementation (2026-10-20):
> Die Kandidatensuche verwendet breite Exploration, mehrere Suchregionen und
> längere Wiederholungsmessungen vor der Profilspeicherung. Bis zu 24 Suchfenster
> mit 16 Messpunkten und sechs Bestätigungen mit 32 Messpunkten, maximal 60 Minuten.
> Candidate search uses broad exploration, multiple regions and longer independent
> confirmation before publishing profiles: up to 24 search windows of 16 samples
> and six confirmation windows of 32 samples, bounded to 60 minutes.
> Siehe / See [current contract / aktueller Vertrag](agent-wiki/auto-tuning.md).

# Autonomous Solar-Mining GPU Optimization Agent

## 1. System Overview
This document specifies the conceptual architecture for an autonomous, local **GPU Auto-Tuning Agent** designed for solar-powered mining operations.

The agent operates as a background service on the **host CPU of the mining rig**. It optimizes GPU parameters using a **Bayesian Optimization Framework** without prior knowledge of whether a mining algorithm is core-heavy (e.g., Pearl, Quantus/Poseidon2) or memory-heavy.

Instead of dynamically searching for parameters during live weather events, the agent uses an **Offline Calibration Phase** to build discrete, high-efficiency operational profiles across a range of power target thresholds. Calibration is performed per **[Algorithm + Specific Mining Software]** combination to account for unique CUDA kernel implementations and varying stability limits across different miner binaries.

The **Solar Power Management System** then switches instantly between these pre-calibrated, fully-optimized profiles based on real-time solar yield.

---

## 2. Core Architectural Concepts

### A. Software-Specific & Algorithm-Aware Calibration
- **Kernel-Level Differences:** Different mining software binaries (e.g., Miner A vs. Miner B) implementing the same underlying algorithm (e.g., Pearl) exert different workload patterns, power spikes, and memory access behaviors on the GPU.
- **Profile Scope:** Calibration profiles are tied specifically to the active tuple of `(Algorithm, Mining Software Binary)`. Changing either the algorithm or the miner binary triggers or loads the corresponding dedicated profile matrix.

### B. Dual-Layer Operational Model
1. **Calibration Layer (Bayesian Optimization Agent on CPU):**
    - Runs periodically or on demand (e.g., upon starting a new mining algorithm or changing the miner binary).
    - Explores the multi-dimensional parameter space (Core Clock/Voltage, Memory Clock, Power Target) using statistical regression (Gaussian Processes).
    - Learns physical GPU limits, identifies optimal voltage/frequency curves, and calculates efficiency scores for the specific miner binary.
    - Generates a set of static, fully optimized operating profiles for different power targets.

2. **Execution Layer (Solar Power Controller):**
    - Monitors live solar generation, battery capacity, and inverter capacity.
    - Instantly applies pre-built profiles from the active profile matrix as solar input fluctuates.
    - Requires zero warm-up or optimization overhead during live switching.

### C. Hardware Interaction & Feedback Loop
- **Hardware Abstraction:** The agent communicates with the GPU via system command interfaces or native vendor management libraries (`nvidia-smi` / NVML) to adjust clocks, voltage offsets, and thermal/power ceilings.
- **Metrics Acquisition:** Reads real-time telemetry (actual core frequency, memory frequency, power draw, thermal limits, valid/invalid shares, and hash output from the mining software).
- **Crash Recovery & Stability Safeguards:**
    - **Hard Crashes (TDR / Driver Timeout):** If a driver timeout or kernel freeze occurs due to aggressive undervolting/overclocking, the agent intercepts the failure, applies a severe penalty score to that parameter region in its statistical model, resets the hardware safely, and continues exploration.
    - **Soft Crashes (Invalid Shares / Calculation Errors):** If the GPU remains operational but produces invalid or rejected shares during an evaluation window, the agent detects this calculation failure, treats it as a "soft crash," and severely penalizes the fitness score for that parameter point to prevent unstable overclocks from being considered valid.

### D. Persistence & Profile Caching
- **Profile Reuse:** Calibrated profile matrices are stored persistently on local storage. Upon system reboots or rig restarts, the agent loads existing cached profiles for the active `[Algorithm + Mining Software]` tuple without re-running the time-consuming calibration loop.
- **Invalidation Triggers:** Full re-calibration is only executed when explicit invalidation triggers occur, such as a software version update, GPU hardware replacement/addition, or a manual user re-calibration request.

---

## 3. Parameter Space & Efficiency Target

### A. Dimensions Explored
- **Core Clock / Voltage Point:** Primary target for core-heavy algorithms.
- **Memory Clock:** Adjusted independently down to driver-minimum thresholds when memory bandwidth is irrelevant to the algorithm (e.g., Quantus/QTC), yielding significant wattage reduction.
- **Power Cap Boundary:** Defines the absolute power envelope for each evaluated operating tier.

### B. Optimization Objective
The agent optimizes for **maximum efficiency** at each target power envelope:

$$\text{Efficiency} = \frac{\text{Hashrate (H/s)}}{\text{Power Consumption (Watts)}}$$

If a parameter combination triggers an instability event (hard driver crash or soft invalid share errors), the efficiency score for that point is reduced to zero (Crash Penalty), teaching the Bayesian model to avoid unsafe hardware regions.

---

## 4. Comparison: Step-Down Power Target vs. Bayesian Profile Generation

| Aspect | Legacy 15W Step-Down Method | Bayesian Profile Optimization Agent |
| :--- | :--- | :--- |
| **Optimization Scope** | 1 Dimension (Power Target Cap only) | Multi-Dimensional (Core, Memory, Voltage, Power Target) |
| **VRAM Power Savings** | None (Memory stays at high stock clocks) | High (Underclocks VRAM on core-heavy algos) |
| **Software Sensitivity** | Generic (Treats all miner software identically) | Tailored (Optimizes specifically per Miner Binary + Algo) |
| **Stability Validation** | Basic (Only checks if GPU driver is alive) | Comprehensive (Handles TDR hard crashes & invalid share soft crashes) |
| **Persistence** | None (Recalculates steps dynamically every session) | High (Caches calibrated profiles persistently across restarts) |
| **Clock Stability** | Poor (Causes dynamic frequency throttling/jumping) | High (Locking frequencies at optimal voltage points) |
| **Real-Time Responsiveness**| Slow (Requires waiting for hash stabilization per step) | Instant (Switches to pre-calculated profiles on solar change) |
| **Algorithmic Adaptability**| Fixed behavior regardless of mining algorithm | Learns optimal tuning for any algorithm & software automatically |

---

## 5. High-Level Operational Workflow

```
             [ Start / New Algorithm or Miner Binary ]
                                │
                                ▼
                   [ Persistent Profile Exists? ]
                   /                            \
             (Yes)/                              \(No / Outdated)
                 ▼                                ▼
    ┌──────────────────────────┐    ┌──────────────────────────────┐
    │ Load Cached Profile Grid │    │ Phase 1: Bayesian Tuning     │
    └────────────┬─────────────┘    │ (Per Software + Algo)        │
                 │                  │ - Sample parameters          │
                 │                  │ - Measure Hash, Wattage,     │
                 │                  │   & Share Validity (Invalids)│
                 │                  │ - Evaluate TDR & Soft Crashes│
                 │                  │ - Build efficiency curve     │
                 │                  └──────────────┬───────────────┘
                 │                                 │
                 │                                 ▼
                 │                  ┌──────────────────────────────┐
                 │                  │ Phase 2: Profile Matrix      │
                 │                  │ - Save & Export profiles     │
                 │                  │   for active [Algo + Miner]  │
                 │                  └──────────────┬───────────────┘
                 │                                 │
                 └────────────────┬────────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ Phase 3: Live Solar Operation│
                   │ - Read solar input           │
                   │ - Switch profile instantly   │
                   └──────────────────────────────┘
```
