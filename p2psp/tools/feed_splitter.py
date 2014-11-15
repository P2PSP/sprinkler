#!/usr/bin/env python

import socket
import time
import hashlib
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--splitter_addr', help='Splitter address', default='127.0.0.1')
parser.add_argument('--splitter_source_port', type=int, default=8080, help='Port where the Splitter listens to the source')
parser.add_argument('--chunk_size', type=int, default=1024, help='size of video chunks')
parser.add_argument('--splitter_pass', help='Password to stream to the splitter', default='hackme')
parser.add_argument('--input_file', help='Path to video file', default='')
parser.add_argument('--input_stream_addr', help='Streaming server address', default='150.214.150.68')
parser.add_argument('--input_stream_port', type=int, help='Streaming server port', default=4551)
parser.add_argument('--input_stream_channel', help='Streaming server channel', default='root/Videos/big_buck_bunny_480p_stereo.ogg')
args = parser.parse_args()

MESSAGE = hashlib.md5(args.splitter_pass).hexdigest()

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((args.splitter_addr, args.splitter_source_port))
s.send(MESSAGE)

data = s.recv(args.chunk_size)

if (data == MESSAGE):
    if (args.input_file == ''):
        source_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        GET_message = 'GET /' + args.input_stream_channel + ' HTTP/1.1\r\n'
        GET_message += '\r\n'
        source = (args.input_stream_addr, args.input_stream_port)
        print source_socket.getsockname(), 'connecting to the source', source, '...'
        source_socket.connect(source)
        print source_socket.getsockname(), 'connected to', source
        source_socket.sendall(GET_message)

        chunk = source_socket.recv(args.chunk_size)
        while chunk:
            s.send(chunk)
            chunk = source_socket.recv(args.chunk_size)
        source_socket.close()
    else:
        f = open(args.input_file, "rb")
        try:
            chunk = f.read(args.chunk_size)

            while chunk:
                s.send(chunk)
                chunk = f.read(args.chunk_size)
                time.sleep(0.004)

        finally:
            f.close()
        f.close()

s.close()
