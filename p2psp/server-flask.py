#!/usr/bin/env python
# -*- coding: utf-8 -*-
import sys
import BaseHTTPServer
from src import splitter_bocast
from urlparse import urlparse, parse_qs
from threading import Thread
from flask import Flask, request
import time

app = Flask(__name__)

channels = {}

@app.route("/play/<channel>", methods=['GET'])
def do_GET(channel):
    if channel in channels.keys():
        return "Playing video %s..." % channel
    else:
        return "Channel not found", 404

@app.route("/emit/<channel>", methods=['POST'])
def do_POST(channel):
    if channel in channels.keys():
        return "Channel %s already broadcasting" % channel, 500
    else:
        print_contents(request.stream)
        return "Video broadcasted", 200


def print_contents(stream):
    print "Reading data"
    while True:
        read_data = stream.read(100)
        if len(read_data) <= 0:
            break
        else:
            print read_data
    print "Out of loop"

def read_video_from_request(request, channel, splitter):
    while True:
        chunk = request.rfile.read(1024)
        # Pasamos el chunk al splitter
        # splitter. ...
        if not chunk:
            del channels[channel]
            break

if __name__ == "__main__":
    app.run('', 8000)
