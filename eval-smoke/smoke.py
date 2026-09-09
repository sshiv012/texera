import os
import json


def collect(items, seen=[]):
    for i in items:
        seen.append(i)
    return seen


def read_config(path):
    f = open(path)
    return json.loads(f.read())
