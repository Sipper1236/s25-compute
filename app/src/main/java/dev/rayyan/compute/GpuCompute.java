package dev.rayyan.compute;

import android.opengl.*;
import java.nio.*;
import org.json.JSONObject;

/** Executes a deterministic vector operation on the GPU and verifies every result. */
final class GpuCompute {
    static JSONObject run(int[] input) throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        int shader = 0, program = 0; int[] buffers = new int[1];
        try {
            if (!EGL14.eglInitialize(display,new int[2],0,new int[2],0)) throw new Exception("EGL initialization failed");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE},0,configs,0,1,count,0);
            if (count[0] == 0) throw new Exception("OpenGL ES 3 config unavailable");
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            surface = EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if (!EGL14.eglMakeCurrent(display,surface,surface,context)) throw new Exception("GPU context unavailable");
            shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);
            GLES31.glShaderSource(shader,"#version 310 es\nlayout(local_size_x=64) in; layout(std430,binding=0) buffer Data { uint values[]; }; void main(){ uint i=gl_GlobalInvocationID.x; if(i<uint(values.length())) values[i]=values[i]*3u+7u; }");
            GLES31.glCompileShader(shader); int[] ok = new int[1]; GLES31.glGetShaderiv(shader,GLES31.GL_COMPILE_STATUS,ok,0);
            if (ok[0] == 0) throw new Exception(GLES31.glGetShaderInfoLog(shader));
            program = GLES31.glCreateProgram(); GLES31.glAttachShader(program,shader); GLES31.glLinkProgram(program); GLES31.glGetProgramiv(program,GLES31.GL_LINK_STATUS,ok,0);
            if (ok[0] == 0) throw new Exception(GLES31.glGetProgramInfoLog(program));
            int size = input.length;
            ByteBuffer initial=ByteBuffer.allocateDirect(size*4).order(ByteOrder.nativeOrder()); initial.asIntBuffer().put(input);
            GLES31.glGenBuffers(1,buffers,0); GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,buffers[0]);
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,size*4,initial,GLES31.GL_DYNAMIC_READ);
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,0,buffers[0]); GLES31.glUseProgram(program);
            long start = System.nanoTime(); GLES31.glDispatchCompute((size+63)/64,1,1); GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT); GLES31.glFinish();
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR) throw new Exception("GPU dispatch failed");
            ByteBuffer mapped = (ByteBuffer) GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,0,size*4,GLES31.GL_MAP_READ_BIT);
            if (mapped == null) throw new Exception("GPU readback failed");
            IntBuffer values = mapped.order(ByteOrder.nativeOrder()).asIntBuffer();
            for (int i=0;i<size;i++) if (values.get(i)!=input[i]*3+7) throw new Exception("GPU result mismatch at " + i);
            org.json.JSONArray output=new org.json.JSONArray(); for(int i=0;i<size;i++) output.put(Integer.toUnsignedLong(values.get(i)));
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            return new JSONObject().put("backend","OpenGL ES 3.1 compute").put("renderer",GLES31.glGetString(GLES31.GL_RENDERER)).put("elements",size).put("verified",true).put("values",output).put("elapsedMs",(System.nanoTime()-start)/1000000.0);
        } finally {
            if (buffers[0]!=0) GLES31.glDeleteBuffers(1,buffers,0);
            if (program!=0) GLES31.glDeleteProgram(program); if (shader!=0) GLES31.glDeleteShader(shader);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if (surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface);
            if (context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
        }
    }
}
