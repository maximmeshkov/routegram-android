/* CPython bootstrap only. No Telegram/proxy protocol code belongs here. */
#include <Python.h>
#include <stdlib.h>
#include <string.h>

static PyObject *command = NULL;

static char *python_error(void) {
    PyObject *error = PyErr_GetRaisedException();
    PyObject *text = error ? PyObject_Str(error) : NULL;
    const char *message = text ? PyUnicode_AsUTF8(text) : NULL;
    char *copy = strdup(message ? message : "Python operation failed");
    Py_XDECREF(text);
    Py_XDECREF(error);
    PyErr_Clear();
    return copy;
}

static PyStatus append_path(PyConfig *config, const char *path) {
    wchar_t *wide = Py_DecodeLocale(path, NULL);
    if (!wide) return PyStatus_NoMemory();
    PyStatus status = PyWideStringList_Append(&config->module_search_paths, wide);
    PyMem_RawFree(wide);
    return status;
}

/* Calls are serialized by the Android wrapper. NULL means success. */
char *RoutegramPythonInit(const char *home, const char *stdlib,
                         const char *packages, const char *native_dir) {
    if (command) return NULL;
    int first = !Py_IsInitialized();
    PyGILState_STATE gil = PyGILState_UNLOCKED;
    if (first) {
        PyConfig config;
        PyConfig_InitIsolatedConfig(&config);
        config.install_signal_handlers = 0;
        config.write_bytecode = 0;
        config.site_import = 0;
        config.parse_argv = 0;
        config.module_search_paths_set = 1;
        PyStatus status = PyConfig_SetBytesString(&config, &config.home, home);
        if (!PyStatus_Exception(status)) status = append_path(&config, stdlib);
        if (!PyStatus_Exception(status)) status = append_path(&config, packages);
        if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
        char *error = PyStatus_Exception(status)
            ? strdup(status.err_msg ? status.err_msg : "Python initialization failed") : NULL;
        PyConfig_Clear(&config);
        if (error) return error;
    } else {
        gil = PyGILState_Ensure();
    }

    PyObject *module = PyImport_ImportModule("routegram_runtime");
    PyObject *configured = module ? PyObject_CallMethod(module, "configure", "s", native_dir) : NULL;
    if (configured) command = PyObject_GetAttrString(module, "command_json");
    char *error = command ? NULL : python_error();
    Py_XDECREF(configured);
    Py_XDECREF(module);
    if (first) PyEval_SaveThread();
    else PyGILState_Release(gil);
    return error;
}

/* Returned UTF-8 storage belongs to the caller; SystemExit is never printed. */
char *RoutegramPythonCall(const char *request) {
    if (!command) return strdup("{\"ok\":false,\"error\":\"Python is not initialized\"}");
    PyGILState_STATE gil = PyGILState_Ensure();
    PyObject *result = PyObject_CallFunction(command, "s", request);
    const char *text = result ? PyUnicode_AsUTF8(result) : NULL;
    char *copy = text ? strdup(text) : NULL;
    if (!copy) {
        PyErr_Clear();
        copy = strdup("{\"ok\":false,\"error\":\"Python command failed\"}");
    }
    Py_XDECREF(result);
    PyGILState_Release(gil);
    return copy;
}

void RoutegramPythonFree(char *text) { free(text); }
