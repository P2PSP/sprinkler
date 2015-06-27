#!/usr/bin/env python
# -*- coding: utf-8 -*-
import sys
import SocketServer
from BaseHTTPServer import HTTPServer, BaseHTTPRequestHandler
from urlparse import urlparse, parse_qs
from threading import Thread
import socket
import os
import time
import subprocess as sp
import shutil

channels = {}

ffserver = None

SERVER_IP = '0.0.0.0'
SERVER_PORT = 8000

FFMPEG_BIN = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
FFSERVER_BIN = "ffserver.exe" if os.name == "nt" else "ffserver"

class ThreadedHttpServer(SocketServer.ThreadingMixIn, HTTPServer):
    pass

class RequestHandler (BaseHTTPRequestHandler):

    def do_GET (self):
        args = urlparse(self.path)
        query_args = parse_qs(args.query)
        channel = None
        if query_args.has_key('channel'):
            channel = query_args['channel'][0]
        if args.path == "/play" and channel is not None:
            if channel in channels.keys():
                self.send_response(200)
            else:
                # couldn't find channel
                self.send_response(404)
        else:
            # the request wasn't properly built
            self.send_response(500)
        self.end_headers()
        return

    def do_POST(self):
        args = urlparse(self.path)
        query_args = parse_qs(args.query)
        channel = None
        if query_args.has_key('channel'):
            channel = query_args['channel'][0]
        if args.path == "/emit" and channel is not None:
            if channel not in channels.keys():

                print "New channel", channel

                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

                sock.bind(('', 0))
                sock.listen(1)

                port = sock.getsockname()[1]

                ffmpeg = sp.Popen([FFMPEG_BIN, "-i", "tcp://localhost:"+str(port), "-y", "http://localhost:8090/"+channel+".ffm"])
                channels[channel] = ffmpeg

                reply_sock, addr = sock.accept()

                parse_stream(self.rfile, reply_sock, channel)

                self.send_response(200)

            else:
                print "Channel already in list"
                self.send_response(500)
        else:
            self.send_response(500)

def parse_stream(origin, splitter_socket, channel):
    # number of bytes to read on hex chars
    bytes_to_read = origin.read(4)
    # real amount of bytes to read
    amount = int(bytes_to_read, 16)
    # skip '\r\n' bytes
    origin.read(2)
    # read the whole 'http chunk' and pass it to the splitter
    read_bytes = origin.read(amount)
    splitter_socket.sendall(read_bytes)

    try:

        while len(read_bytes) > 0:
            # if not 1st chunk, '\r\n' is also before the chunk size
            origin.read(2)
            bytes_to_read = origin.read(4)

            amount = int(bytes_to_read, 16)
            origin.read(2)
            read_bytes = origin.read(amount)
            splitter_socket.sendall(read_bytes)

    except Exception:
        pass

    print "\nTransmission from channel '"+channel+"' ended\n"

    # If transmission ended
    channels[channel].terminate()
    del channels[channel]

def main (args):
    ffserver = sp.Popen([FFSERVER_BIN, "-f", "ffserver.conf"])

    _prepareTmpFolder()

    # create a threaded http server, each request is handled in a new thread
    httpd = ThreadedHttpServer((SERVER_IP, SERVER_PORT), RequestHandler)
    httpd.daemon_threads = True
    httpd.serve_forever()

    time.sleep(2)
    print "Everything is ready"

    try:
        while 1:
            time.sleep(.1)
    except KeyboardInterrupt:
        if ffserver is not None:
            sp.terminate()

        for channel, process in channels.iteritems():
            if process is not None:
                process.terminate()

def _prepareTmpFolder():
    # If tmp folder exists, delete it
    try:
        shutil.rmtree("tmp")
    except OSError:
        pass

    os.mkdir("tmp")

if __name__ == "__main__":
    sys.exit(main(sys.argv))



