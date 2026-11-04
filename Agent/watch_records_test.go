package main

import (
	"encoding/binary"
	"reflect"
	"testing"
	"unicode/utf16"
)

func encodeDirectoryChanges(changes ...directoryChange) []byte {
	var data []byte
	for i, change := range changes {
		name := utf16.Encode([]rune(change.path))
		size := 12 + 2*len(name)
		size = (size + 3) &^ 3
		record := make([]byte, size)
		if i < len(changes)-1 {
			binary.LittleEndian.PutUint32(record, uint32(size))
		}
		binary.LittleEndian.PutUint32(record[4:], change.action)
		binary.LittleEndian.PutUint32(record[8:], uint32(2*len(name)))
		for j, c := range name {
			binary.LittleEndian.PutUint16(record[12+2*j:], c)
		}
		data = append(data, record...)
	}
	return data
}

func TestDecodeDirectoryChangesPreservesBothRenamePathsAndUnicode(t *testing.T) {
	data := encodeDirectoryChanges(
		directoryChange{4, `旧目录\项目📁`}, directoryChange{5, `新目录\项目📁`},
		directoryChange{2, `新目录\项目📁\删除.txt`})
	want := []directoryChange{{4, "旧目录/项目📁"}, {5, "新目录/项目📁"}, {2, "新目录/项目📁/删除.txt"}}
	got, err := decodeDirectoryChanges(data)
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("got=%+v err=%v", got, err)
	}
}

func TestDecodeDirectoryChangesLeavesApplicationFilteringToConsumer(t *testing.T) {
	// The parent-directory guard also receives sibling names outside the source.
	// An unrelated .syncsrv sibling must not invalidate this task's coverage.
	got, err := decodeDirectoryChanges(encodeDirectoryChanges(directoryChange{1, ".syncsrv"}))
	if err != nil || len(got) != 1 || got[0].path != ".syncsrv" {
		t.Fatalf("got=%+v err=%v", got, err)
	}
}

func TestDecodeDirectoryChangesRejectsLostOrUnsafeRecords(t *testing.T) {
	valid := encodeDirectoryChanges(directoryChange{2, "file"})
	cases := map[string][]byte{
		"empty": nil, "header": valid[:11], "name": valid[:15],
		"unknown action": encodeDirectoryChanges(directoryChange{99, "file"}),
		"escape":         encodeDirectoryChanges(directoryChange{1, `..\outside`}),
		"absolute":       encodeDirectoryChanges(directoryChange{1, `C:\outside`}),
		"empty name":     encodeDirectoryChanges(directoryChange{1, ""}),
	}
	for name, value := range map[string]uint32{"unaligned": 13, "overlapping": 4, "past end": 128, "overflow": ^uint32(0)} {
		data := append([]byte(nil), valid...)
		binary.LittleEndian.PutUint32(data, value)
		cases[name] = data
	}
	odd := append([]byte(nil), valid...)
	binary.LittleEndian.PutUint32(odd[8:], 3)
	cases["odd UTF16 length"] = odd
	for name, data := range cases {
		t.Run(name, func(t *testing.T) {
			if got, err := decodeDirectoryChanges(data); err == nil || len(got) != 0 {
				t.Fatalf("accepted corrupt notification: %+v, %v", got, err)
			}
		})
	}
}
