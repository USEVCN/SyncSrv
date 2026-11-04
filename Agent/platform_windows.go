package main

import (
	"golang.org/x/sys/windows"
	"unsafe"
)

var processMemory = windows.NewLazySystemDLL("psapi.dll").NewProc("GetProcessMemoryInfo")

type memoryCounters struct {
	Size, PageFaults                                                                                             uint32
	PeakWorkingSet, WorkingSet, PeakPagedPool, PagedPool, PeakNonPagedPool, NonPagedPool, Pagefile, PeakPagefile uintptr
}

func syncDirectory(string) error { return nil }
func processUsage() (float64, int64) {
	var a, b, k, u windows.Filetime
	if windows.GetProcessTimes(windows.CurrentProcess(), &a, &b, &k, &u) != nil {
		return 0, 0
	}
	var memory memoryCounters
	memory.Size = uint32(unsafe.Sizeof(memory))
	processMemory.Call(uintptr(windows.CurrentProcess()), uintptr(unsafe.Pointer(&memory)), uintptr(memory.Size))
	return float64((uint64(k.HighDateTime)<<32|uint64(k.LowDateTime))+(uint64(u.HighDateTime)<<32|uint64(u.LowDateTime))) / 1e7, int64(memory.PeakWorkingSet)
}
func protectDirectory(path string) error {
	user, e := windows.GetCurrentProcessToken().GetTokenUser()
	if e != nil {
		return e
	}
	descriptor, e := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;" + user.User.Sid.String() + ")")
	if e != nil {
		return e
	}
	dacl, _, e := descriptor.DACL()
	if e != nil {
		return e
	}
	return windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, dacl, nil)
}
