import Foundation
import PowerLogCore

#if os(iOS)
  import AVFoundation

  final class AppleTonePlayer: NSObject, ApplicationTonePlayer {
    private let clock: ApplicationClock
    private let queue: DispatchQueue
    private let audio = DispatchQueue(label: "app.powerlog.tones", qos: .userInitiated)
    private var playing: Playing?

    private final class Playing {
      let reply: ApplicationReply<ApplicationToneOnset>
      let engine = AVAudioEngine()
      let player = AVAudioPlayerNode()
      var observers: [NSObjectProtocol] = []
      var onset: ApplicationToneOnset?

      init(reply: ApplicationReply<ApplicationToneOnset>) { self.reply = reply }
    }

    init(clock: ApplicationClock, queue: DispatchQueue) {
      self.clock = clock
      self.queue = queue
    }

    func play(tones: [DomainTone], reply: ApplicationReply<ApplicationToneOnset>) {
      audio.async { [self] in start(tones, reply) }
    }

    func stop() {
      audio.async { [self] in end(playing, kind: .cancelled, platform: nil) }
    }

    private func start(_ tones: [DomainTone], _ reply: ApplicationReply<ApplicationToneOnset>) {
      end(playing, kind: .cancelled, platform: nil)
      guard let sync = tones.last else { return answer { reply.failed(error: Self.error(.unsupported, nil)) } }
      let current = Playing(reply: reply)
      playing = current
      let session = AVAudioSession.sharedInstance()
      do {
        try session.setCategory(.playback, mode: .default, options: [.duckOthers])
        try session.setActive(true)
        guard
          let format = AVAudioFormat(standardFormatWithSampleRate: Double(sync.sampleRateHz), channels: 1)
        else { return end(current, kind: .unsupported, platform: nil) }
        current.engine.attach(current.player)
        current.engine.connect(current.player, to: current.engine.mainMixerNode, format: format)
        observe(current, session: session)
        try current.engine.start()
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        let host = HostClock(
          monoUs: clock.monoUs(), hostTicks: mach_absolute_time(), numer: timebase.numer, denom: timebase.denom)
        let latencyUs = Int64(session.outputLatency * 1_000_000)
        for (index, tone) in tones.enumerated() {
          guard let buffer = Self.buffer(tone, format) else { return end(current, kind: .unsupported, platform: nil) }
          let last = index == tones.count - 1
          current.player.scheduleBuffer(
            buffer, at: AVAudioTime(hostTime: host.ticks(atMonoUs: tone.atMonoUs - latencyUs)),
            completionCallbackType: .dataPlayedBack
          ) { [weak self] _ in
            guard last else { return }
            self?.audio.async { self?.end(current, kind: nil, platform: nil) }
          }
        }
        current.player.play()
        let lateUs = max(0, host.monoUs(atTicks: mach_absolute_time()) + latencyUs - sync.atMonoUs)
        let onset = ApplicationToneOnset(lateUs: lateUs, route: Self.route(session))
        current.onset = onset
        let delay = max(0, Double(sync.atMonoUs - clock.monoUs()) / 1_000_000)
        audio.asyncAfter(deadline: .now() + delay) { [weak self] in
          guard let self, self.playing === current else { return }
          self.answer { current.reply.ok(value: onset) }
        }
      } catch {
        let code = error as NSError
        end(
          current, kind: Self.kind(code),
          platform: DomainPlatformCode(domain: code.domain, code: String(code.code)))
      }
    }

    private func observe(_ current: Playing, session: AVAudioSession) {
      let center = NotificationCenter.default
      let interrupted: (Notification) -> Void = { [weak self] _ in
        self?.audio.async { self?.end(current, kind: .busy, platform: nil) }
      }
      current.observers = [
        center.addObserver(
          forName: AVAudioSession.interruptionNotification, object: session, queue: nil, using: interrupted),
        center.addObserver(
          forName: .AVAudioEngineConfigurationChange, object: current.engine, queue: nil, using: interrupted),
      ]
    }

    private func end(_ current: Playing?, kind: ApplicationPortErrorKind?, platform: DomainPlatformCode?) {
      guard let current, playing === current else { return }
      playing = nil
      for observer in current.observers { NotificationCenter.default.removeObserver(observer) }
      current.player.stop()
      current.engine.stop()
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
      if let kind {
        answer { current.reply.failed(error: Self.error(kind, platform)) }
      } else if let onset = current.onset {
        answer { current.reply.ok(value: onset) }
      }
    }

    private func answer(_ block: @escaping () -> Void) {
      queue.async(execute: block)
    }

    private static func buffer(_ tone: DomainTone, _ format: AVAudioFormat) -> AVAudioPCMBuffer? {
      let count = Int(tone.samples.size)
      guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count)),
        let channel = buffer.floatChannelData?[0]
      else { return nil }
      buffer.frameLength = AVAudioFrameCount(count)
      for index in 0..<count { channel[index] = tone.samples.get(index: Int32(index)) }
      return buffer
    }

    private static func route(_ session: AVAudioSession) -> ApplicationAudioRoute {
      switch session.currentRoute.outputs.first?.portType {
      case .builtInSpeaker?: return .speaker
      case .headphones?, .usbAudio?, .lineOut?: return .wired
      case .bluetoothA2DP?, .bluetoothLE?, .bluetoothHFP?: return .bluetooth
      default: return .other
      }
    }

    private static func kind(_ error: NSError) -> ApplicationPortErrorKind {
      switch AVAudioSession.ErrorCode(rawValue: error.code) {
      case .cannotStartPlaying?: return .notallowedinbackground
      case .insufficientPriority?, .isBusy?: return .busy
      default: return .failed
      }
    }

    private static func error(_ kind: ApplicationPortErrorKind, _ platform: DomainPlatformCode?) -> ApplicationPortError
    {
      ApplicationPortError(kind: kind, platform: platform)
    }
  }
#endif
