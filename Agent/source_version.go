package main

import (
	"os"
	"reflect"
)

// File IDs can be reused immediately after unlink. Keep the full timestamp and
// the platform's change/creation marker as well, without another content pass.
func sameSourceVersion(a, b os.FileInfo) bool {
	if !os.SameFile(a, b) || a.Size() != b.Size() || !a.ModTime().Equal(b.ModTime()) {
		return false
	}
	left, right := reflect.Indirect(reflect.ValueOf(a.Sys())), reflect.Indirect(reflect.ValueOf(b.Sys()))
	if !left.IsValid() || !right.IsValid() || left.Kind() != reflect.Struct || right.Kind() != reflect.Struct {
		return true
	}
	for _, name := range []string{"Ctim", "Ctimespec", "CreationTime"} {
		x, y := left.FieldByName(name), right.FieldByName(name)
		if x.IsValid() && y.IsValid() && x.CanInterface() && y.CanInterface() {
			return reflect.DeepEqual(x.Interface(), y.Interface())
		}
	}
	return true
}
