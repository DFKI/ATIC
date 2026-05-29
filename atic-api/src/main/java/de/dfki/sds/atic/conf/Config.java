

package de.dfki.sds.atic.conf;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Config {
    String value();
    String description() default "";
}
