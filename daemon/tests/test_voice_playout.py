"""PCM continuity at the real CallSession playout boundary, without a room."""

import asyncio
from types import SimpleNamespace

from agentring.voice.backend import AudioOut, Interrupted
from agentring.voice.session import CallInfo, CallSession, VoiceAgent


def test_packet_arriving_within_queued_audio_headroom_does_not_insert_silence(monkeypatch):
    async def scenario():
        captured = []
        first_drained = asyncio.Event()
        following_played = asyncio.Event()
        first = b"\x11\x00" * 240
        following = b"\x22\x00" * 240

        class Source:
            queued_duration = 0.2

            async def capture_frame(self, frame):
                pcm = bytes(frame.data.cast("B"))
                captured.append(pcm)
                if pcm == first:
                    first_drained.set()
                if pcm == following:
                    following_played.set()
                    await asyncio.Event().wait()
                await asyncio.sleep(0)

        from agentring.voice import session as module

        monkeypatch.setattr(module.rtc, "AudioSource", lambda *a, **kw: Source())
        session = CallSession(
            VoiceAgent(lambda: SimpleNamespace(input_rate=24000, output_rate=24000), ""),
            CallInfo("continuity"),
        )
        session.handle_event(AudioOut(first))
        playing = asyncio.create_task(session._play_loop())
        try:
            await first_drained.wait()
            # The next TTS packet is delayed, while 200 ms is still queued.
            await asyncio.sleep(0.002)
            session.handle_event(AudioOut(following))
            await asyncio.wait_for(following_played.wait(), 0.1)
        finally:
            playing.cancel()
            await asyncio.gather(playing, return_exceptions=True)
        assert captured == [first, following], "Do not cut continuous speech with fabricated zero frames"

    asyncio.run(scenario())


def test_interruption_wakes_headroom_wait_and_clears_queued_speech(monkeypatch):
    async def scenario():
        first_played = asyncio.Event()
        silence_played = asyncio.Event()
        captured = []
        first = b"\x11\x00" * 240

        class Source:
            queued_duration = 0.2
            cleared = False

            def clear_queue(self):
                self.cleared = True
                self.queued_duration = 0

            async def capture_frame(self, frame):
                pcm = bytes(frame.data.cast("B"))
                captured.append(pcm)
                if pcm == first:
                    first_played.set()
                    await asyncio.sleep(0)
                else:
                    silence_played.set()
                    await asyncio.Event().wait()

        from agentring.voice import session as module

        source = Source()
        monkeypatch.setattr(module.rtc, "AudioSource", lambda *a, **kw: source)
        session = CallSession(
            VoiceAgent(lambda: SimpleNamespace(input_rate=24000, output_rate=24000), ""),
            CallInfo("interrupt"),
        )
        session.handle_event(AudioOut(first))
        playing = asyncio.create_task(session._play_loop())
        try:
            await first_played.wait()
            await asyncio.sleep(0.002)
            assert captured == [first]
            session.handle_event(Interrupted())
            await asyncio.wait_for(silence_played.wait(), 0.05)
        finally:
            playing.cancel()
            await asyncio.gather(playing, return_exceptions=True)
        assert source.cleared and not session.output
        assert captured == [first, bytes(480)]

    asyncio.run(scenario())


def test_real_producer_stall_eventually_sends_silence(monkeypatch):
    async def scenario():
        first_played = asyncio.Event()
        silence_played = asyncio.Event()
        captured = []
        first = b"\x11\x00" * 240

        class Source:
            queued_duration = 0.025

            async def capture_frame(self, frame):
                pcm = bytes(frame.data.cast("B"))
                captured.append(pcm)
                if pcm == first:
                    first_played.set()
                    await asyncio.sleep(0)
                else:
                    silence_played.set()
                    await asyncio.Event().wait()

        from agentring.voice import session as module

        monkeypatch.setattr(module.rtc, "AudioSource", lambda *a, **kw: Source())
        session = CallSession(
            VoiceAgent(lambda: SimpleNamespace(input_rate=24000, output_rate=24000), ""),
            CallInfo("stall"),
        )
        session.handle_event(AudioOut(first))
        playing = asyncio.create_task(session._play_loop())
        try:
            await first_played.wait()
            await asyncio.sleep(0.002)
            assert not silence_played.is_set(), "Use native queued audio before filling a gap"
            await asyncio.wait_for(silence_played.wait(), 0.15)
        finally:
            playing.cancel()
            await asyncio.gather(playing, return_exceptions=True)
        assert captured == [first, bytes(480)]

    asyncio.run(scenario())
