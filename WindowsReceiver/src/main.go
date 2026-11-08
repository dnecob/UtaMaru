// VoicePitchLooper WindowsReceiver — prototype, Windows 10/11 x64.
// No third-party dependencies; uses Windows winmm waveOut to play 48kHz PCM to VB-CABLE.
// Audio endpoint is typically named "CABLE Input" (PLAYBACK); capture CABLE Output in Discord.
package main

import (
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

const (
	frameSamples = 960
	frameBytes   = frameSamples * 2
	stereoBytes  = frameSamples * 4
	slotCount    = 8
	whdrDone     = 0x00000001
)

type waveOutCaps struct {
	Mid           uint16
	Pid           uint16
	DriverVersion uint32
	ProductName   [32]uint16
	Formats       uint32
	Channels      uint16
	Reserved      uint16
	Support       uint32
}

type waveFormatEx struct {
	FormatTag      uint16
	Channels       uint16
	SampleRate     uint32
	BytesPerSecond uint32
	BlockAlign     uint16
	BitsPerSample  uint16
	ExtraSize      uint16
}

type waveHdr struct {
	Data          uintptr
	Length        uint32
	BytesRecorded uint32
	User          uintptr
	Flags         uint32
	Loops         uint32
	Next          uintptr
	Reserved      uintptr
}

var (
	winmm         = syscall.NewLazyDLL("winmm.dll")
	getNumDevs    = winmm.NewProc("waveOutGetNumDevs")
	getDevCaps    = winmm.NewProc("waveOutGetDevCapsW")
	waveOpen      = winmm.NewProc("waveOutOpen")
	wavePrepare   = winmm.NewProc("waveOutPrepareHeader")
	waveWrite     = winmm.NewProc("waveOutWrite")
	waveReset     = winmm.NewProc("waveOutReset")
	waveUnprepare = winmm.NewProc("waveOutUnprepareHeader")
	waveClose     = winmm.NewProc("waveOutClose")
)

type device struct {
	id   int
	name string
}

func devices() []device {
	count, _, _ := getNumDevs.Call()
	res := make([]device, 0)
	for i := 0; i < int(count); i++ {
		var caps waveOutCaps
		status, _, _ := getDevCaps.Call(uintptr(i), uintptr(unsafe.Pointer(&caps)), unsafe.Sizeof(caps))
		if status != 0 {
			continue
		}
		name := string(utf16.Decode(caps.ProductName[:]))
		name = strings.TrimRight(name, "\x00")
		res = append(res, device{i, name})
	}
	return res
}

type slot struct {
	hdr      waveHdr
	data     []byte
	pinned   runtime.Pinner
	queued   bool
	prepared bool
}

type player struct {
	handle uintptr
	slots  [slotCount]slot
	next   int
}

func (p *player) release() {
	if p.handle != 0 {
		waveReset.Call(p.handle)
	}
	for i := range p.slots {
		s := &p.slots[i]
		if p.handle != 0 && s.prepared {
			waveUnprepare.Call(p.handle, uintptr(unsafe.Pointer(&s.hdr)), unsafe.Sizeof(waveHdr{}))
		}
		s.pinned.Unpin()
		s.prepared = false
		s.data = nil
	}
	if p.handle != 0 {
		waveClose.Call(p.handle)
		p.handle = 0
	}
}

func newPlayer(deviceID int) (_ *player, err error) {
	p := &player{}
	defer func() {
		if err != nil {
			p.release()
		}
	}()
	format := waveFormatEx{
		FormatTag: 1, Channels: 2, SampleRate: 48000,
		BytesPerSecond: 48000 * 4, BlockAlign: 4, BitsPerSample: 16,
	}
	res, _, _ := waveOpen.Call(
		uintptr(unsafe.Pointer(&p.handle)), uintptr(deviceID),
		uintptr(unsafe.Pointer(&format)), 0, 0, 0)
	if res != 0 {
		return nil, fmt.Errorf("waveOutOpen failed (code=%d); check CABLE Input format", res)
	}
	for i := range p.slots {
		s := &p.slots[i]
		s.data = make([]byte, stereoBytes)
		s.pinned.Pin(&s.hdr)
		s.pinned.Pin(&s.data[0])
		s.hdr.Data = uintptr(unsafe.Pointer(&s.data[0]))
		s.hdr.Length = stereoBytes
		res, _, _ := wavePrepare.Call(p.handle, uintptr(unsafe.Pointer(&s.hdr)), unsafe.Sizeof(waveHdr{}))
		if res != 0 {
			return nil, fmt.Errorf("waveOutPrepareHeader failed (code=%d)", res)
		}
		s.prepared = true
	}
	return p, nil
}

func (p *player) play(mono []byte) error {
	s := &p.slots[p.next]
	hdr := &s.hdr
	deadline := time.Now().Add(2 * time.Second)
	for s.queued && hdr.Flags&whdrDone == 0 {
		if time.Now().After(deadline) {
			return fmt.Errorf("Windows audio output stalled")
		}
		time.Sleep(2 * time.Millisecond)
	}
	dest := s.data
	for i := 0; i < frameSamples; i++ {
		dest[i*4] = mono[i*2]
		dest[i*4+1] = mono[i*2+1]
		dest[i*4+2] = mono[i*2]
		dest[i*4+3] = mono[i*2+1]
	}
	res, _, _ := waveWrite.Call(p.handle, uintptr(unsafe.Pointer(&s.hdr)), unsafe.Sizeof(waveHdr{}))
	if res != 0 {
		return fmt.Errorf("waveOutWrite failed (code=%d)", res)
	}
	s.queued = true
	p.next = (p.next + 1) % slotCount
	return nil
}

func (p *player) reset() {
	waveReset.Call(p.handle)
	for i := range p.slots {
		p.slots[i].queued = false
	}
	p.next = 0
}

func receive(conn net.Conn, p *player) error {
	defer conn.Close()
	defer p.reset()
	_ = conn.SetReadDeadline(time.Time{})
	buf := make([]byte, frameBytes)
	frames := 0
	for {
		_, err := io.ReadFull(conn, buf)
		if err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				fmt.Printf("Disconnected; frames: %d\n", frames)
				return nil
			}
			return err
		}
		if err = p.play(buf); err != nil {
			return err
		}
		frames++
	}
}

func main() {
	port := flag.Int("port", 50005, "TCP port")
	lan := flag.Bool("lan", false, "allow connections from local Wi-Fi network (0.0.0.0)")
	deviceID := flag.Int("device", -1, "Windows audio output device number; default: auto-detect CABLE Input")
	list := flag.Bool("list", false, "list available playback devices")
	flag.Parse()
	fmt.Println("VoicePitchLooper - Windows PC Mic Receiver v0.2")
	all := devices()
	fmt.Println("Playback devices (select CABLE Input; NOT CABLE Output):")
	for _, d := range all {
		fmt.Printf("  %d: %s\n", d.id, d.name)
	}
	if *list {
		return
	}
	if *deviceID < 0 {
		for _, d := range all {
			if strings.Contains(strings.ToLower(d.name), "cable input") {
				*deviceID = d.id
				break
			}
		}
	}
	if *deviceID < 0 {
		fmt.Println("ERROR: No 'CABLE Input' playback device found. Install VB-CABLE and reboot.")
		fmt.Println("Run with -device N to manually select a playback device; see -list.")
		os.Exit(2)
	}
	fmt.Printf("Using playback device #%d\n", *deviceID)
	p, err := newPlayer(*deviceID)
	if err != nil {
		fmt.Println("ERROR:", err)
		os.Exit(2)
	}
	defer p.release()
	addr := "127.0.0.1"
	if *lan {
		addr = "0.0.0.0"
	}
	listener, err := net.Listen("tcp", fmt.Sprintf("%s:%d", addr, *port))
	if err != nil {
		fmt.Println("ERROR:", err)
		os.Exit(2)
	}
	defer listener.Close()
	fmt.Printf("READY: listening on %s:%d\n", addr, *port)
	if *lan {
		fmt.Println("Wi-Fi mode: set the PHONE host to this PC's local IPv4 address. Allow Private network firewall access only.")
	}
	fmt.Println("USB mode: run adb reverse tcp:50005 tcp:50005 and use 127.0.0.1 on PHONE.")
	fmt.Println("Windows mic apps must select recording device: CABLE Output.")
	for {
		conn, err := listener.Accept()
		if err != nil {
			fmt.Println("Accept error:", err)
			return
		}
		fmt.Println("CONNECTED:", conn.RemoteAddr())
		if err := receive(conn, p); err != nil {
			fmt.Println("Audio connection error:", err)
		}
		fmt.Println("READY for next connection")
	}
}
