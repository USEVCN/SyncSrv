package main

import (
	"fmt"
	"golang.org/x/sys/windows"
)

func validateLocalDirectory(path string) error {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	volume := make([]uint16, 32768)
	if err = windows.GetVolumePathName(name, &volume[0], uint32(len(volume))); err != nil {
		return err
	}
	if windows.GetDriveType(&volume[0]) == windows.DRIVE_REMOTE {
		return fmt.Errorf("remote shares require an agent on the storage host: %s", path)
	}
	return nil
}
