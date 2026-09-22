package com.jlloc.daemon;

import com.jlloc.common.protocol.LocalAuth;
import com.jlloc.common.protocol.ProtocolConstants;
import com.jlloc.common.protocol.StatusCommand;
import com.jlloc.common.protocol.StatusResponse;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonSocketServerTest {

    @Test
    void rejectsMissingOrInvalidTokenAndAcceptsAuthenticatedCli() throws Exception {
        DaemonSocketServer server = new DaemonSocketServer(new ProcessRepository());
        try {
            server.start();

            try (Socket unauthenticated = new Socket("127.0.0.1", server.getPort());
                 DataOutputStream out = new DataOutputStream(unauthenticated.getOutputStream());
                 DataInputStream in = new DataInputStream(unauthenticated.getInputStream())) {
                out.writeUTF("not-the-daemon-token");
                out.flush();
                assertThrows(java.io.IOException.class, in::readUTF);
            }

            assertTrue(LocalAuth.readToken().length() >= 40);

            try (Socket authenticated = new Socket("127.0.0.1", server.getPort());
                 DataOutputStream authOut = new DataOutputStream(authenticated.getOutputStream());
                 DataInputStream authIn = new DataInputStream(authenticated.getInputStream())) {
                authOut.writeUTF(LocalAuth.readToken());
                authOut.flush();
                assertEquals(ProtocolConstants.PROTOCOL_VERSION, authIn.readUTF());

                try (ObjectOutputStream objectOut = new ObjectOutputStream(authenticated.getOutputStream());
                     ObjectInputStream objectIn = new ObjectInputStream(authenticated.getInputStream())) {
                    objectOut.writeObject(new StatusCommand());
                    objectOut.flush();
                    assertTrue(objectIn.readObject() instanceof StatusResponse);
                }
            }
        } finally {
            server.stop();
        }
    }
}
