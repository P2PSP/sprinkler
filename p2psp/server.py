#!/usr/bin/env python
# -*- coding: utf-8 -*-
import sys
import SocketServer
from BaseHTTPServer import HTTPServer, BaseHTTPRequestHandler
from src import splitter_bocast
from urlparse import urlparse, parse_qs
from threading import Thread

channels = {}

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
                self.send_response(404)
        else:
            self.send_response(500)
        self.end_headers()
        #self.wfile.write(self._get_status())
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

                new_splitter.set_source(self.rfile)
                new_splitter.start_streaming()

                #self.send_response(200)

            else:
                print "Channel already in list"
                self.send_response(500)
        else:
            self.send_response(500)

def print_contents(request):
    print "Reading data"
    while request.rfile is not None:
        read_data = request.rfile.read(100)
        if len(read_data) <= 0:
            break
        else:
            print read_data
    print "Out of loop"
    request.send_response(200)

def read_video_from_request(request, channel, splitter):
    while True:
        chunk = request.rfile.read(1024)
        # Pasamos el chunk al splitter
        # splitter. ...
        if not chunk:
            del channels[channel]
            break


def main (args):
    httpd = ThreadedHttpServer(('192.168.1.129', 8000), RequestHandler)
    httpd.serve_forever()

if __name__ == "__main__":
    sys.exit(main(sys.argv))
