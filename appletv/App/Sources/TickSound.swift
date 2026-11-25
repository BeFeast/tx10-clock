import AVFoundation
import Foundation
import TX10ClockCore

/// Synthesized soundbar-keepalive tick (tvOS-only `tickSound` extension).
///
/// The source node renders continuously — digital silence between ticks keeps
/// a BT / HDMI-ARC audio link streaming so the soundbar never auto-suspends.
/// The tick itself is a short 880 Hz sine burst with a fast exponential decay,
/// synthesized in the render callback; there are no audio assets.
final class TickSoundPlayer {

    private let engine = AVAudioEngine()
    private var sourceNode: AVAudioSourceNode?
    private var mode: TickSound = .minute
    private var active = false
    private var observers: [NSObjectProtocol] = []

    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            self?.handleInterruption(note)
        })
        observers.append(center.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            self?.rebuild()
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] _ in
            self?.rebuild()
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    func apply(mode: TickSound) {
        guard mode != self.mode else { return }
        self.mode = mode
        rebuild()
    }

    func setActive(_ active: Bool) {
        guard active != self.active else { return }
        self.active = active
        rebuild()
    }

    private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else {
            return
        }
        if type == .ended {
            rebuild()
        }
    }

    /// The render callback reads only state frozen at engine start, so any
    /// runtime change (mode, interruption, route) does a full stop + restart
    /// instead of mutating shared state under the realtime thread.
    private func rebuild() {
        stopEngine()
        guard active, mode != .off else {
            try? AVAudioSession.sharedInstance()
                .setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        startEngine(interval: mode == .second ? 1.0 : 60.0)
    }

    private func stopEngine() {
        if engine.isRunning {
            engine.stop()
        }
        if let node = sourceNode {
            engine.detach(node)
            sourceNode = nil
        }
    }

    private func startEngine(interval: Double) {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback)
            try session.setActive(true)
        } catch {
            return
        }

        let outputRate = engine.outputNode.outputFormat(forBus: 0).sampleRate
        let sampleRate = outputRate > 0 ? outputRate : 48_000
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate,
                                         channels: 1) else {
            return
        }

        let renderer = TickRenderer(sampleRate: sampleRate, interval: interval)
        let node = AVAudioSourceNode(format: format) { _, timestamp, frameCount, audioBufferList in
            renderer.render(timestamp: timestamp,
                            frameCount: Int(frameCount),
                            into: audioBufferList)
            return noErr
        }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        do {
            try engine.start()
            sourceNode = node
        } catch {
            engine.detach(node)
        }
    }
}

/// Realtime render state. After init it is touched only by the audio render
/// thread; `TickSoundPlayer` rebuilds the whole engine for any change.
private final class TickRenderer {

    private let sampleRate: Double
    private let interval: Double
    private let tickFrames: Int
    private let hostTicksToSeconds: Double
    private let startEpoch: Double
    private let startHostTime: UInt64

    private var nextTickEpoch: Double = .infinity
    private var envelopeFrame: Int = -1
    private var framesRendered: Double = 0

    // Gentle up-close tick: ~35 ms burst, 880 Hz, ~8 ms decay, peak ~0.06.
    private let burstSeconds = 0.035
    private let frequency = 880.0
    private let decaySeconds = 0.008
    private let peakAmplitude = 0.06

    init(sampleRate: Double, interval: Double) {
        self.sampleRate = sampleRate
        self.interval = interval
        self.tickFrames = Int(burstSeconds * sampleRate)
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        self.hostTicksToSeconds = Double(info.numer) / Double(info.denom) / 1_000_000_000
        self.startEpoch = Date().timeIntervalSince1970
        self.startHostTime = mach_absolute_time()
    }

    func render(timestamp: UnsafePointer<AudioTimeStamp>,
                frameCount: Int,
                into audioBufferList: UnsafeMutablePointer<AudioBufferList>) {
        let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
        for buffer in buffers where buffer.mData != nil {
            memset(buffer.mData, 0, Int(buffer.mDataByteSize))
        }
        guard let out = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else {
            framesRendered += Double(frameCount)
            return
        }

        // Boundaries are epoch-aligned: multiples of 1 s / 60 s in Unix time
        // coincide with wall-clock second and minute boundaries.
        let epoch = bufferStartEpoch(timestamp)
        if nextTickEpoch == .infinity {
            nextTickEpoch = (epoch / interval).rounded(.down) * interval + interval
        }

        for frame in 0..<frameCount {
            let t = epoch + Double(frame) / sampleRate
            if t >= nextTickEpoch {
                envelopeFrame = 0
                repeat {
                    nextTickEpoch += interval
                } while nextTickEpoch <= t
            }
            if envelopeFrame >= 0 && envelopeFrame < tickFrames {
                let et = Double(envelopeFrame) / sampleRate
                let envelope = exp(-et / decaySeconds)
                out[frame] = Float(peakAmplitude * envelope * sin(2 * .pi * frequency * et))
                envelopeFrame += 1
            }
        }
        framesRendered += Double(frameCount)
    }

    private func bufferStartEpoch(_ timestamp: UnsafePointer<AudioTimeStamp>) -> Double {
        let ts = timestamp.pointee
        if ts.mFlags.contains(.hostTimeValid) {
            let delta = Double(Int64(bitPattern: ts.mHostTime &- startHostTime))
            return startEpoch + delta * hostTicksToSeconds
        }
        return startEpoch + framesRendered / sampleRate
    }
}
