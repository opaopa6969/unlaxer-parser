use crate::generated::{
    ast::Ast,
    evaluator::{evaluate, Semantics},
};
use unlaxer_runtime::Span;

pub struct Calculator;

impl Semantics for Calculator {
    type Output = Result<f64, String>;

    fn eval_number(&mut self, value: &str, _span: Span) -> Self::Output {
        value.parse::<f64>().map_err(|error| error.to_string())
    }

    fn eval_binary(&mut self, left: &Ast, op: &str, right: &Ast, _span: Span) -> Self::Output {
        let left = evaluate(left, self)?;
        let right = evaluate(right, self)?;
        match op {
            "+" => Ok(left + right),
            "*" => Ok(left * right),
            _ => Err(format!("unknown operator: {op}")),
        }
    }

    fn eval_negation(&mut self, value: &Ast, _span: Span) -> Self::Output {
        Ok(-evaluate(value, self)?)
    }

    fn eval_conditional(
        &mut self,
        condition: &Ast,
        then_expr: &Ast,
        else_expr: &Ast,
        _span: Span,
    ) -> Self::Output {
        if evaluate(condition, self)? != 0.0 {
            evaluate(then_expr, self)
        } else {
            evaluate(else_expr, self)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::generated::{mapper, parser};

    #[test]
    fn evaluates_generated_tree_with_owned_positions() {
        let tree = parser::parse_tree("if(0,neg(2*3),4+5)").unwrap();
        let ast = mapper::map(&tree).unwrap();
        drop(tree);
        assert_eq!(evaluate(&ast, &mut Calculator), Ok(9.0));
        assert_eq!(ast.span(), Span { start: 0, end: 18 });
    }
}
