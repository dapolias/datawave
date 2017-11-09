package nsa.datawave.query.rewrite.jexl;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.jexl2.JexlArithmetic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nsa.datawave.query.rewrite.jexl.functions.JexlFunctionNamespaceRegistry;

/**
 * A Cache of JexlEngines, key'ed off of the name of the JexlArithmetic class used to create the JexlEngine
 * 
 */
public class ArithmeticJexlEngines {
    private static final Logger log = LoggerFactory.getLogger(ArithmeticJexlEngines.class);
    private static final Map<Class<? extends JexlArithmetic>,RefactoredDatawaveJexlEngine> engineCache = new ConcurrentHashMap<>();
    private static final Map<String,Object> registeredFunctions = JexlFunctionNamespaceRegistry.getConfiguredFunctions();
    
    private ArithmeticJexlEngines() {}
    
    /**
     * This convenience method can be used to interpret the result of the script.execute() result.
     * 
     * @param scriptExecuteResult
     * @return true if we matched, false otherwise.
     */
    public static boolean isMatched(Object scriptExecuteResult) {
        return RefactoredDatawaveInterpreter.isMatched(scriptExecuteResult);
    }
    
    public static RefactoredDatawaveJexlEngine getEngine(JexlArithmetic arithmetic) {
        if (null == arithmetic) {
            return null;
        }
        
        Class<? extends JexlArithmetic> arithmeticClass = arithmetic.getClass();
        
        if (!engineCache.containsKey(arithmeticClass)) {
            RefactoredDatawaveJexlEngine engine = createEngine(arithmetic);
            
            if (arithmetic instanceof StatefulArithmetic == false) {
                // do not cache an Arithmetic that has state
                engineCache.put(arithmeticClass, engine);
            }
            
            return engine;
        }
        
        return engineCache.get(arithmeticClass);
    }
    
    private static RefactoredDatawaveJexlEngine createEngine(JexlArithmetic arithmetic) {
        RefactoredDatawaveJexlEngine engine = new RefactoredDatawaveJexlEngine(null, arithmetic, registeredFunctions, null);
        engine.setCache(1024);
        engine.setSilent(false);
        
        // Setting strict to be true causes an Exception when a field
        // in the query does not occur in the document being tested.
        // This doesn't appear to have any unexpected consequences looking
        // at the Interpreter class in JEXL.
        engine.setStrict(false);
        
        return engine;
    }
    
    /**
     * Returns an modifiable view of the current namespace to function class mappings.
     */
    public static Map<String,Object> functions() {
        return Collections.unmodifiableMap(registeredFunctions);
    }
    
}