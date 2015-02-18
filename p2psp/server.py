#!/usr/bin/env python
# -*- coding: utf-8 -*-
import sys
import SocketServer
from BaseHTTPServer import HTTPServer, BaseHTTPRequestHandler
from src import splitter_bocast
from urlparse import urlparse, parse_qs
from threading import Thread
import socket

channels = {}

SERVER_IP = '192.168.1.129'
SERVER_PORT = 8000

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

                args = {}
                new_splitter = splitter_bocast.Splitter(args)

                print "New channel", channel
                channels[channel] = new_splitter

                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                # a better port assignation policy is needed
                sock.bind(('', 9998))
                sock.listen(1)

                new_splitter.set_source(9998)

                reply_sock, addr = sock.accept()

                read_thread = Thread(target=parse_stream, args=(self.rfile,reply_sock))
                read_thread.start()

                new_splitter.start_streaming()

            else:
                print "Channel already in list"
                self.send_response(500)
        else:
            self.send_response(500)

def parse_stream(origin, splitter_socket):
    # number of bytes to read on hex chars
    bytes_to_read = origin.read(3)
    # real amount of bytes to read
    amount = int(bytes_to_read, 16)
    # skip '\r\n' bytes
    origin.read(2)
    # read the whole 'http chunk' and pass it to the splitter
    read_bytes = origin.read(amount)
    splitter_socket.sendall(read_bytes)
    while len(read_bytes) > 0:
        # if not 1st chunk, '\r\n' is also before the chunk size
        origin.read(2)
        bytes_to_read = origin.read(3)
        amount = int(bytes_to_read, 16)
        origin.read(2)
        read_bytes = origin.read(amount)
        splitter_socket.sendall(read_bytes)

def main (args):
    # create a threaded http server, each request is handled in a new thread
    httpd = ThreadedHttpServer((SERVER_IP, SERVER_PORT), RequestHandler)
    httpd.serve_forever()

if __name__ == "__main__":
    sys.exit(main(sys.argv))
