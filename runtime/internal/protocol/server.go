// Package protocol implements the experimental v1 JSON-lines worker contract.
package protocol

import (
	"bufio"
	"bytes"
	"dev.runtimebay/worker/internal/supervisor"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"runtime"
)

const Version = 1
const WorkerVersion = "0.1.0-prototype"
const MaxRequestBytes = 64 * 1024
const MaxResponseBytes = 256 * 1024
const MaxRequests = 4096

var capabilities = []string{"process.start.v1", "process.observe.v1", "process.stop.v1", "session.cleanup.v1"}

type Request struct {
	Version   int               `json:"version"`
	ID        string            `json:"id"`
	Operation string            `json:"operation"`
	TargetID  string            `json:"targetId"`
	SessionID string            `json:"sessionId,omitempty"`
	Requires  []string          `json:"requires,omitempty"`
	Handle    string            `json:"handle,omitempty"`
	Start     *supervisor.Start `json:"start,omitempty"`
}
type Failure struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}
type Response struct {
	Version   int      `json:"version"`
	ID        string   `json:"id"`
	TargetID  string   `json:"targetId"`
	SessionID string   `json:"sessionId"`
	Result    any      `json:"result,omitempty"`
	Error     *Failure `json:"error,omitempty"`
}
type Handshake struct {
	WorkerVersion string   `json:"workerVersion"`
	OS            string   `json:"os"`
	Arch          string   `json:"arch"`
	Capabilities  []string `json:"capabilities"`
	Ownership     string   `json:"ownership"`
}

// Serve owns all child handles until input EOF or protocol termination.
// The caller establishes the transport and trusted target identity; no listener exists.
func Serve(input io.Reader, output io.Writer, target string) (result error) {
	if target == "" || len(target) > 128 {
		return errors.New("a bounded target identity is required")
	}
	session, err := supervisor.Token()
	if err != nil {
		return err
	}
	manager := supervisor.New()
	defer func() { result = errors.Join(result, manager.Close()) }()
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 4096), MaxRequestBytes)
	ready := false
	seen := make(map[string]bool)
	for scanner.Scan() {
		var req Request
		response := Response{Version: Version, TargetID: target, SessionID: session}
		decoder := json.NewDecoder(bytes.NewReader(scanner.Bytes()))
		decoder.DisallowUnknownFields()
		decodeErr := decoder.Decode(&req)
		if decodeErr == nil {
			var extra any
			if decoder.Decode(&extra) != io.EOF {
				decodeErr = errors.New("one JSON object required")
			}
		}
		response.ID = req.ID
		fail := func(code, message string) { response.Error = &Failure{code, message} }
		switch {
		case decodeErr != nil:
			fail("invalid_request", "expected one JSON request with known fields")
		case len(req.ID) == 0 || len(req.ID) > 128:
			fail("invalid_request", "id must contain 1 to 128 bytes")
		case seen[req.ID]:
			fail("duplicate_operation", "operation ID was already used; observe before retrying a mutation")
		case len(seen) >= MaxRequests:
			fail("session_limit", "request limit reached; close the worker session")
		case req.Version != Version:
			fail("incompatible_version", "only protocol version 1 is supported")
		case req.TargetID != target:
			fail("target_mismatch", "request target differs from this worker target")
		default:
			seen[req.ID] = true
			supported := true
			for _, required := range req.Requires {
				found := false
				for _, capability := range capabilities {
					if required == capability {
						found = true
					}
				}
				if !found {
					supported = false
				}
			}
			switch {
			case !supported:
				fail("unsupported_capability", "a required capability is unavailable")
			case req.Operation == "handshake":
				ready = true
				response.Result = Handshake{WorkerVersion, runtime.GOOS, runtime.GOARCH, append([]string{}, capabilities...), "session-scoped; input EOF terminates owned groups; no reconnect adoption"}
			case !ready:
				fail("handshake_required", "perform a compatible capability handshake before operations")
			case req.SessionID != session:
				fail("session_mismatch", "operation belongs to a different worker session")
			default:
				var value supervisor.Snapshot
				var operationErr error
				switch req.Operation {
				case "process.start":
					if req.Start == nil {
						operationErr = errors.New("start parameters are required")
					} else {
						value, operationErr = manager.Start(*req.Start)
					}
				case "process.observe":
					value, operationErr = manager.Observe(req.Handle)
				case "process.stop":
					value, operationErr = manager.Stop(req.Handle)
				default:
					fail("unsupported_operation", "operation is unavailable")
				}
				if operationErr != nil {
					fail("operation_failed", operationErr.Error())
				} else if response.Error == nil {
					response.Result = value
				}
			}
		}
		data, err := json.Marshal(response)
		if err != nil {
			return err
		}
		if len(data) > MaxResponseBytes {
			return errors.New("response bound exceeded")
		}
		if _, err = fmt.Fprintf(output, "%s\n", data); err != nil {
			return err
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("request stream rejected: %w", err)
	}
	return nil
}
