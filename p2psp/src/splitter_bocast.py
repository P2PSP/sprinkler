#!/usr/bin/python -O
# -*- coding: iso-8859-15 -*-

# This code is distributed under the GNU General Public License (see
# THE_GENERAL_GNU_PUBLIC_LICENSE.txt for extending this information).
# Copyright (C) 2014, the P2PSP team.
# http://www.p2psp.org

# {{{ Imports

from __future__ import print_function
import hashlib
import time
import sys
import socket
import threading
import struct
import argparse
from color import Color
import common
from splitter_ims_bocast import Splitter_IMS
from splitter_dbs_bocast import Splitter_DBS
from splitter_fns import Splitter_FNS
from splitter_acs import Splitter_ACS
from splitter_lrs import Splitter_LRS
from _print_ import _print_

# }}}

class Splitter():

    splitter = None

    mcast = False

    def __init__(self, args):

        _print_("Running in", end=' ')
        if __debug__:
            print("debug mode")
        else:
            print("release mode")

        # {{{ Args parsing and instantiation

        if 'buffer_size' in args:
            Splitter_IMS.BUFFER_SIZE = args['buffer_size']

        if 'chunk_size' in args:
            Splitter_IMS.CHUNK_SIZE = ['chunk_size']

        if 'header_size' in args:
            Splitter_IMS.HEADER_SIZE = ['header_size']

        if 'port' in args:
            Splitter_IMS.port = args['port']

        if 'source_addr' in args:
            Splitter_IMS.SOURCE_ADDR = socket.gethostbyname(args['source_addr'])

        if 'source_pass' in args:
            Splitter_IMS.SOURCE_PASS = hashlib.md5(args['source_pass']).hexdigest()

        if 'mcast' in args:
            self.mcast = True
            print("IP multicast mode selected")

            if 'mcast_addr' in args:
                Splitter_IMS.MCAST_ADDR = args['mcast_addr']

            self.splitter = Splitter_IMS()
            self.splitter.peer_list = []

        else:
            self.splitter = Splitter_DBS()
            #splitter = Splitter_FNS()
            #splitter = Splitter_ACS()
            #splitter = Splitter_LRS()

            if 'max_chunk_loss' in args:
                self.splitter.MAX_CHUNK_LOSS = int(args['max_chunk_loss'])

        # }}}

    def set_source(self, file):
        self.splitter.set_source(file)

    def start_streaming(self):
        # {{{ Run!

        self.splitter.start()

        # {{{ Prints information until keyboard interruption

        print("         | Received | Sent      |")
        print("    Time | (kbps)   | (kbps)    | Peers (#, peer, losses, period, kbps) ")
        print("---------+----------+-----------+-----------------------------------...")

        last_sendto_counter = self.splitter.sendto_counter
        last_recvfrom_counter = self.splitter.recvfrom_counter

        while self.splitter.alive:
            try:
                time.sleep(1)
                kbps_sendto = ((self.splitter.sendto_counter - last_sendto_counter) * self.splitter.CHUNK_SIZE * 8) / 1000
                kbps_recvfrom = ((self.splitter.recvfrom_counter - last_recvfrom_counter) * self.splitter.CHUNK_SIZE * 8) / 1000
                last_sendto_counter = self.splitter.sendto_counter
                last_recvfrom_counter = self.splitter.recvfrom_counter
                sys.stdout.write(Color.white)
                _print_("|" + repr(kbps_recvfrom).rjust(10) + "|" + repr(kbps_sendto).rjust(10), end=" | ")
                #print('%5d' % splitter.chunk_number, end=' ')
                sys.stdout.write(Color.cyan)
                print(len(self.splitter.peer_list), end=' ')
                if not __debug__:
                    counter = 0
                for p in self.splitter.peer_list:
                    if not __debug__:
                        if counter > 10:
                            break
                        counter += 1
                    sys.stdout.write(Color.blue)
                    print(p, end= ' ')
                    sys.stdout.write(Color.red)
                    print('%3d' % self.splitter.losses[p], self.splitter.MAX_CHUNK_LOSS, end=' ')
                    try:
                        sys.stdout.write(Color.blue)
                        print('%3d' % self.splitter.period[p], end= ' ')
                        sys.stdout.write(Color.purple)
                        print(repr((self.splitter.number_of_sent_chunks_per_peer[p] * self.splitter.CHUNK_SIZE * 8) / 1000).rjust(4), end = ' ')
                        self.splitter.number_of_sent_chunks_per_peer[p] = 0
                    except AttributeError:
                        pass
                    sys.stdout.write(Color.none)
                    print('|', end=' ')
                print()

            except KeyboardInterrupt:
                print('Keyboard interrupt detected ... Exiting!')

                # Say to the daemon threads that the work has been finished,
                self.splitter.alive = False

                # Wake up the "moderate_the_team" daemon, which is waiting
                # in a cluster_sock.recvfrom(...).
                if self.mcast:
                    self.splitter.say_goodbye(("127.0.0.1", self.splitter.PORT), self.splitter.team_socket)

                # Wake up the "handle_arrivals" daemon, which is waiting
                # in a peer_connection_sock.accept().
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.connect(("127.0.0.1", self.splitter.PORT))
                sock.recv(struct.calcsize("4sH")) # Multicast channel
                sock.recv(struct.calcsize("H")) # Header size
                sock.recv(struct.calcsize("H")) # Chunk size
                sock.recv(self.splitter.CHUNK_SIZE*self.splitter.HEADER_SIZE) # Header
                sock.recv(struct.calcsize("H")) # Buffer size
                if self.mcast:
                    number_of_peers = 0
                else:
                    number_of_peers = socket.ntohs(struct.unpack("H", sock.recv(struct.calcsize("H")))[0])
                    print("Number of peers =", number_of_peers)
                # Receive the list
                while number_of_peers > 0:
                    sock.recv(struct.calcsize("4sH"))
                    number_of_peers -= 1

                # Breaks this thread and returns to the parent process
                # (usually, the shell).
                break

            # }}}

        # }}}

    def get_port(self):
        return self.splitter.port