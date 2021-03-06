package com.paritytrading.philadelphia.client;

import java.util.List;
import java.util.Scanner;

class WaitCommand implements Command {

    private static final long WAIT_TIME_MILLIS = 50;

    @Override
    public void execute(TerminalClient client, Scanner arguments) {
        if (!arguments.hasNext())
            throw new IllegalArgumentException();

        String msgType = arguments.next();

        if (arguments.hasNext())
            throw new IllegalArgumentException();

        while (true) {
            try {
                Thread.sleep(WAIT_TIME_MILLIS);

                if (msgType.equals(getLastMsgType(client.getMessages())))
                    break;

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public String getName() {
        return "wait";
    }

    @Override
    public String getDescription() {
        return "Wait for a specific MsgType(35)";
    }

    @Override
    public String getUsage() {
        return "wait <msg-type>";
    }

    private static String getLastMsgType(Messages messages) {
        List<Message> collection = messages.collect();

        if (collection.isEmpty())
            return null;

        return collection.get(collection.size() - 1).getMsgType();
    }

}
