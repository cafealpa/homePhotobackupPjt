package com.homephoto.server;

/** Child process for timeout/output tests; no external binary or network is required. */
public class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("hang")) {
            System.out.println("started");
            System.out.flush();
            Thread.sleep(60000);
        } else if (args[0].equals("fail")) {
            for (int i = 0; i < 10000; i++) System.out.println("process diagnostic output");
            System.out.println("final failure marker");
            System.exit(7);
        } else {
            System.out.println("done");
        }
    }
}
