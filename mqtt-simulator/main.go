package main

import (
	"log"

	"mqtt-simulator/internal/server"
)

func main() {
	srv, err := server.New()
	if err != nil {
		log.Fatalf("create server: %v", err)
	}

	if err = srv.Run(); err != nil {
		log.Fatalf("run server: %v", err)
	}
}
