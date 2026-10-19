package main

import (
	"os"

	"github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

func main() {
	if err := cmd.Execute(); err != nil {
		os.Exit(1)
	}
}
