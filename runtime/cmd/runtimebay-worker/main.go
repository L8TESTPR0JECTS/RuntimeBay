package main

import (
	"dev.runtimebay/worker/internal/protocol"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	target := flag.String("target-id", "", "control-plane supplied machine identity")
	flag.Parse()
	// Closing input on parent termination follows the same cleanup path as EOF.
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-signals; _ = os.Stdin.Close() }()
	if err := protocol.Serve(os.Stdin, os.Stdout, *target); err != nil {
		fmt.Fprintln(os.Stderr, "runtimebay-worker:", err)
		os.Exit(1)
	}
}
