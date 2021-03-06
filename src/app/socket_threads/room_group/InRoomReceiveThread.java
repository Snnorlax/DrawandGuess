package app.socket_threads.room_group;

import app.*;
import srm.ReliableMulticastSocket;

import javax.swing.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.time.Instant;
import java.util.Collections;

/**
 * This thread receives all incoming messages within the room.
 * Either a player object or a room object would be received.
 * Updates the information accordingly.
 */
public class InRoomReceiveThread extends Thread {
    public volatile boolean interrupted = false;
    @Override
    public void run() {
        ReliableMulticastSocket socket = MySocketFactory.newInstance(DrawandGuess.currentRoom.IP, DrawandGuess.currentRoom.port);
        while (!interrupted) {
            DatagramPacket p = new DatagramPacket(new byte[65507], 65507);
            socket.receive(p);

            // We determine the type by parsing into one type and checking if a must-have field is null.
            Player player = DrawandGuess.gson.fromJson(new String(p.getData(), 0, p.getLength()), Player.class);
            if (player.name == null) {
                Room room = DrawandGuess.gson.fromJson(new String(p.getData(), 0, p.getLength()), Room.class);
                synchronized (DrawandGuess.currentRoom) {
                    DrawandGuess.currentRoom.roomName = room.roomName;
                    DrawandGuess.currentRoom.dictionary = room.dictionary;
                    DrawandGuess.currentRoom.host = room.host;
                    DrawandGuess.currentRoom.numRounds = room.numRounds;
                    DrawandGuess.currentRoom.inGame = room.inGame;
                    DrawandGuess.currentRoom.initWords = room.initWords;
                    DrawandGuess.currentRoom.numPlayers = room.numPlayers;
                    DrawandGuess.currentRoom.notifyAll();

                    synchronized (DrawandGuess.self) {
                        if (DrawandGuess.currentRoom.inGame && !DrawandGuess.self.inGame) {
                            DrawandGuess.self.inGame = true;
                            WhiteBoardGUI.redirectTo(WhiteBoardGUI.waitingRoom, WhiteBoardGUI.drawPane);
                            WhiteBoardGUI.frame.setTitle("Drawing Phase");
                            int index = DrawandGuess.currentRoom.playerList.indexOf(DrawandGuess.self);
                            String initWord = (String) JOptionPane.showInputDialog(null,
                                    "Select starting word",
                                    "Starting word",
                                    JOptionPane.QUESTION_MESSAGE, null,
                                    DrawandGuess.currentRoom.initWords.get(index).toArray(),
                                    DrawandGuess.currentRoom.initWords.get(index).get(0));
                            if (initWord==null) {
                                initWord = DrawandGuess.currentRoom.initWords.get(index).get(0);
                            }
                            DrawandGuess.self.guessedList.add(initWord);
                            WhiteBoardGUI.setPrevWord("Starting word: " + initWord);
                        }
                    }

                }

            } else {
                player.lastActive = Instant.now().toEpochMilli();
                synchronized (DrawandGuess.currentRoom) {
                    DrawandGuess.currentRoom.playerList.remove(player);
                    DrawandGuess.currentRoom.playerList.add(player);
                    Collections.sort(DrawandGuess.currentRoom.playerList);
                    DrawandGuess.currentRoom.notifyAll();
                }
            }
            synchronized (DrawandGuess.currentRoom) {
                if (DrawandGuess.currentRoom.allDone()) {

                    // end of round
                    if (DrawandGuess.turn == DrawandGuess.currentRoom.numTurn) {
                        if (DrawandGuess.self.isHost) {
                            DrawandGuess.currentRoom.generateInitWords();
                        }
                        WhiteBoardGUI.showPane = new ShowPane();
                        WhiteBoardGUI.redirectTo(WhiteBoardGUI.wait, WhiteBoardGUI.showPane);
                        WhiteBoardGUI.frame.setTitle("Showing Results");
                        try {
                            WhiteBoardGUI.showPane.showing();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        synchronized (DrawandGuess.self) {
                            if (DrawandGuess.self.round == DrawandGuess.currentRoom.numRounds) {
                                WhiteBoardGUI.redirectTo(WhiteBoardGUI.showPane, WhiteBoardGUI.end);
                                WhiteBoardGUI.frame.setTitle("Thanks For Playing");
                                DrawandGuess.self.round++;
                            } else {
                                DrawandGuess.turn = 0;
                                DrawandGuess.self.round++;
                                DrawandGuess.self.guessedList.clear();
                                DrawandGuess.self.drawingList.clear();

                                WhiteBoardGUI.drawPane = new DrawPane();
                                WhiteBoardGUI.redirectTo(WhiteBoardGUI.showPane, WhiteBoardGUI.drawPane);
                                WhiteBoardGUI.frame.setTitle("Drawing Phase");

                                int index = DrawandGuess.currentRoom.playerList.indexOf(DrawandGuess.self);
                                String initWord = (String) JOptionPane.showInputDialog(null,
                                        "Select starting word",
                                        "Starting word",
                                        JOptionPane.QUESTION_MESSAGE, null,
                                        DrawandGuess.currentRoom.initWords.get(index).toArray(),
                                        DrawandGuess.currentRoom.initWords.get(index).get(0));
                                if (initWord == null) {
                                    initWord = DrawandGuess.currentRoom.initWords.get(index).get(0);
                                }
                                DrawandGuess.self.guessedList.add(initWord);
                                WhiteBoardGUI.setPrevWord("Starting word: " + initWord);
                            }
                        }
                    } else {
                        // even turn guess
                        if (DrawandGuess.turn % 2 == 0) {
                            WhiteBoardGUI.drawPane = new DrawPane();
                            WhiteBoardGUI.redirectTo(WhiteBoardGUI.wait, WhiteBoardGUI.drawPane);
                            WhiteBoardGUI.frame.setTitle("Drawing Phase");
                        } else {
                            WhiteBoardGUI.guessPane = new GuessPane();
                            WhiteBoardGUI.redirectTo(WhiteBoardGUI.wait, WhiteBoardGUI.guessPane);
                            WhiteBoardGUI.frame.setTitle("Guessing Phase");
                        }
                    }
                    DrawandGuess.turn++;
                }
            }
        }
        try {
            socket.leaveGroup(DrawandGuess.LOBBY_SOCKET_ADDRESS, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
        socket.close();
    }
}
