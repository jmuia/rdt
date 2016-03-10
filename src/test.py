import os
import subprocess
from itertools import product


def main():

    methods = ["GoBackN", "StopAndWait"]
    filenames = ["s_sm_file", "s_md_file", "s_lg_file"]
    reliability_numbers = [0, 10, 100]
    window_sizes = [10, 40, 80]

    combinations = list(product(methods, filenames, reliability_numbers, window_sizes))

    if not os.path.exists("test.out"):
        header = "Method,Timeout,File Size,Window Size,Reliability Number,Transfer Time\n"
    else:
        header = None

    f = open("test.out", mode="a+")
    if header:
        f.write(header)

    for m, fn, rn, ws in combinations:
        receiver_ps = rec_process(m, rn)
        sender_ps = snd_process(m, fn, ws)

        receiver_ps.communicate()
        out, err = sender_ps.communicate()

        data = out.split("\n")[-2]
        o_to, o_fs, o_ws, o_tt = data.split(",")
        info = map(str, [m, o_to, o_fs, o_ws, rn, o_tt])

        f.write(",".join(info) + "\n")

    f.close()


def rec_process(m, rn):
    receiver_cmd = "java {method}Receiver localhost 5000 8000 {rn} recfile"
    cmd = receiver_cmd.format(method=m, rn=rn).split(" ")
    return subprocess.Popen(cmd)


def snd_process(m, fn, ws):
    sender_cmd = "java {method}Sender localhost 8000 5000 {fn} {ws}"
    cmd = sender_cmd.format(method=m, fn=fn, ws=ws).split(" ")

    if m == "StopAndWait":
        cmd = cmd[:-1]

    return subprocess.Popen(cmd, stdout=subprocess.PIPE)


if __name__ == "__main__":
    main()
