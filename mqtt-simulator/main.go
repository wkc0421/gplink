package main

import (
	"log"
	"os"

	"mqtt-simulator/internal/server"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)
	log.Printf("mqtt-simulator starting pid=%d", os.Getpid())

	srv, err := server.New()
	if err != nil {
		log.Fatalf("create server: %v", err)
	}

	log.Printf("server created successfully")
	if err = srv.Run(); err != nil {
		log.Fatalf("run server: %v", err)
	}

	log.Printf("mqtt-simulator exited normally")
}
